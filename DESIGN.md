# Design Document — Multithreaded HTTP/1.1 Server + Encrypted Adaptive Streaming Origin

## 1. Project Overview

This project builds a multithreaded HTTP/1.1 web server from scratch in Java — no frameworks — starting directly from raw TCP sockets. A `ServerSocket` accepts client connections, with each connection handed to a fixed `ExecutorService` thread pool so multiple clients are processed concurrently using reusable worker threads. Each worker runs a custom HTTP parser handling request lines, headers, and request bodies via `Content-Length`, passes parsed requests through a router to application handlers, and writes properly formatted HTTP responses with correct status codes including 200, 206, 400, 404, 405, and 500. The server supports GET/POST APIs, static file serving with path-traversal protection, request logging with per-thread visibility, keep-alive connections with configurable idle timeouts, and graceful shutdown.

On top of this HTTP core, the server is extended into an adaptive video-streaming origin supporting both encrypted and unencrypted **HLS** and **MPEG-DASH** streams. It serves HLS playlists (`.m3u8`) and MPEG-TS segments alongside MPEG-DASH MPD manifests and fMP4 segments, with protocol-correct MIME types and cache headers. For protected HLS delivery, the server implements **AES-128 segment encryption**: playlists include the `#EXT-X-KEY` directive, encrypted segments are served through the same HTTP infrastructure, and a dedicated key-delivery endpoint releases decryption keys only to authorized requests. Unencrypted streams remain fully supported, giving a baseline for comparing protected and unprotected media delivery.

Media delivery is completed with proper HTTP **Range request** support (206 Partial Content), enabling players to seek efficiently within large media files. Persistent HTTP/1.1 **keep-alive** connections with idle timeouts let a viewer's many playlist and segment requests reuse a single TCP connection instead of paying connection-setup cost per request. A browser-based player using **hls.js** and **dash.js** validates the full pipeline end to end — TCP handling → HTTP parsing → routing → auth → encryption → range delivery → playback.

Finally, the system is validated with a Python benchmark harness simulating concurrent viewers fetching realistic playlist-and-segment request sequences against **1, 4, 8, and 16** worker-thread configurations, measuring throughput, average latency, and **p50/p95/p99** latency. Hardware, software, configuration, and workload are documented so every reported number is measured, reproducible, and verifiable.

> **Scoping note:** the server does not transcode or package video. Media segments (`.ts`, `.m4s`), playlists, and manifests are generated once, offline, with `ffmpeg`, and checked into the repo as fixtures under `media/`. The server's job is to *serve* that pre-packaged content correctly — routing, headers, encryption-key gating, range handling, and concurrency — which is what actually gets benchmarked and is what maps to real HTTP-server engineering skills.

### Core Architecture

```text
HTTP Client → TCP → ServerSocket → Thread Pool → HTTP Parser → Router → Handler → HTTP Response
                                                                    │
                              ┌─────────────────────────────────────┼─────────────────────────────┐
                              ▼                                     ▼                             ▼
                       API Handlers                          Static/Range                  Streaming Handlers
                    (Root, Hello, Echo)                    (StaticFileHandler)         (HlsHandler, DashHandler, KeyHandler)
```

Concurrency model (unchanged from the core plan):

> Accept connections sequentially, but process each connection concurrently using a fixed thread pool. Keep-alive connections are re-read in a loop on the same worker thread until the client closes or the idle timeout fires.

---

## 2. Project Structure

```text
multithreaded-http-server/
├── pom.xml
├── README.md
├── .gitignore
├── src/
│   ├── main/
│   │   ├── java/com/ankith/httpserver/
│   │   │   ├── Main.java
│   │   │   ├── server/
│   │   │   │   ├── HttpServer.java
│   │   │   │   ├── ClientHandler.java
│   │   │   │   └── KeepAliveManager.java
│   │   │   ├── http/
│   │   │   │   ├── HttpRequest.java
│   │   │   │   ├── HttpResponse.java
│   │   │   │   ├── HttpParser.java
│   │   │   │   ├── HttpStatus.java
│   │   │   │   └── RangeParser.java
│   │   │   ├── router/
│   │   │   │   ├── Router.java
│   │   │   │   ├── Route.java
│   │   │   │   └── Handler.java
│   │   │   ├── handler/
│   │   │   │   ├── RootHandler.java
│   │   │   │   ├── EchoHandler.java
│   │   │   │   └── StaticFileHandler.java
│   │   │   ├── streaming/
│   │   │   │   ├── HlsHandler.java
│   │   │   │   ├── DashHandler.java
│   │   │   │   ├── KeyHandler.java
│   │   │   │   └── MimeTypes.java
│   │   │   ├── metrics/
│   │   │   │   ├── MetricsRegistry.java
│   │   │   │   └── MetricsHandler.java
│   │   │   ├── config/ServerConfig.java
│   │   │   └── logging/RequestLogger.java
│   │   └── resources/
│   │       └── public/
│   │           ├── index.html
│   │           ├── style.css
│   │           ├── test.txt
│   │           └── player/
│   │               └── player.html        # hls.js + dash.js validation page
│   └── test/java/com/ankith/httpserver/
│       ├── HttpParserTest.java
│       ├── RouterTest.java
│       ├── RangeParserTest.java
│       └── StaticFileHandlerTest.java
├── media/                                 # pre-packaged fixtures, generated offline
│   ├── prepare-media.sh                   # ffmpeg packaging script (source of truth)
│   ├── hls-clear/
│   │   ├── playlist.m3u8
│   │   └── seg-000.ts ... seg-NNN.ts
│   ├── hls-encrypted/
│   │   ├── playlist.m3u8                  # contains #EXT-X-KEY
│   │   ├── stream.key                     # raw AES-128 key (server-side only)
│   │   └── seg-000.ts ... seg-NNN.ts      # encrypted
│   └── dash/
│       ├── manifest.mpd
│       ├── init.m4s
│       └── seg-000.m4s ... seg-NNN.m4s
├── benchmark/
│   ├── benchmark.py                       # core HTTP throughput/latency
│   ├── streaming_benchmark.py             # playlist+segment request-sequence simulation
│   └── results/
└── docs/
    └── architecture.md
```

Create components progressively — core first, then range/keep-alive, then streaming, then metrics.

---

## 3. Core HTTP Components (unchanged from the base plan)

### `HttpServer.java`

```java
ExecutorService threadPool = Executors.newFixedThreadPool(workerThreads);

while (running) {
    Socket client = serverSocket.accept();
    threadPool.submit(new ClientHandler(client, router, config));
}
```

### `ClientHandler.java`

With keep-alive, one worker now handles a **loop of requests** on the same socket, not just one:

```text
while (connectionOpen && !idleTimeoutExpired) {
    request  = HttpParser.parse(inputStream)
    response = router.route(request)
    write(response)
    connectionOpen = shouldKeepAlive(request, response)
}
close(socket)
```

Responsibilities per iteration: read → parse → route → handle → respond → log. Decide `Connection: keep-alive` vs `close` from the request header and `HTTP/1.1` defaults (keep-alive unless `Connection: close` is explicit).

### HTTP status codes in scope

```java
public enum HttpStatus {
    OK(200, "OK"),
    PARTIAL_CONTENT(206, "Partial Content"),
    BAD_REQUEST(400, "Bad Request"),
    UNAUTHORIZED(401, "Unauthorized"),
    NOT_FOUND(404, "Not Found"),
    METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
    RANGE_NOT_SATISFIABLE(416, "Range Not Satisfiable"),
    INTERNAL_SERVER_ERROR(500, "Internal Server Error");
}
```

---

## 4. Range Request Support

`RangeParser.java` parses a single-range `Range` header:

```text
Range: bytes=1000-1999    → start=1000, end=1999
Range: bytes=1000-        → start=1000, end=EOF
Range: bytes=-500         → last 500 bytes
```

Behavior:

- No `Range` header → `200 OK`, full body, `Accept-Ranges: bytes` on responses that support it.
- Valid range → `206 Partial Content` with `Content-Range: bytes start-end/total` and only the requested bytes in the body.
- Range outside file bounds → `416 Range Not Satisfiable` with `Content-Range: bytes */total`.
- Multi-range (`bytes=0-100,200-300`) is **out of scope** — return the first range only or `416`; document this as a known limitation.

Applies to `StaticFileHandler`, `HlsHandler`, and `DashHandler` — any endpoint serving a file from disk should share the same range-serving logic (put it in one shared method, don't duplicate it per handler).

---

## 5. Keep-Alive Connections

`KeepAliveManager.java` (or logic inline in `ClientHandler`) owns:

- Idle timeout (`socket.setSoTimeout(idleTimeoutMs)`), configurable via `ServerConfig`.
- Deciding keep-alive vs close per response (respect client's `Connection` header; default to keep-alive on HTTP/1.1).
- Always sending `Content-Length` (or defined framing) so the client knows where a response ends without closing the socket.

**Document this tradeoff explicitly in the README:** a fixed thread pool + keep-alive means an idle-but-open connection still occupies a worker thread until it times out or closes. Under high concurrent viewer counts this can starve the pool faster than short-lived connections would. This is a legitimate, intentional discussion point — not a bug to silently hide.

---

## 6. Streaming Module

### Media preparation (`media/prepare-media.sh`, run offline — not server code)

```bash
# Clear HLS
ffmpeg -i source.mp4 -codec copy -start_number 0 \
  -hls_time 4 -hls_list_size 0 -f hls hls-clear/playlist.m3u8

# Encrypted HLS (AES-128)
openssl rand 16 > hls-encrypted/stream.key
echo "hls-encrypted/stream.key" > enc.keyinfo
echo "/hls/encrypted/key" >> enc.keyinfo   # key URI written into playlist
ffmpeg -i source.mp4 -codec copy -start_number 0 \
  -hls_time 4 -hls_list_size 0 -hls_key_info_file enc.keyinfo \
  -f hls hls-encrypted/playlist.m3u8

# DASH (fMP4)
ffmpeg -i source.mp4 -codec copy -f dash dash/manifest.mpd
```

This script is part of the repo (reproducibility) but its output — not the script itself — is what the server serves.

### `HlsHandler.java`

Routes:

```text
GET /hls/{stream}/playlist.m3u8
GET /hls/{stream}/{segment}.ts
```

- Serve `playlist.m3u8` with `Content-Type: application/vnd.apple.mpegurl`.
- Serve `.ts` segments with `Content-Type: video/mp2t`, through the shared range-serving path.
- `{stream}` is either `clear` or `encrypted`, mapped to `media/hls-clear/` or `media/hls-encrypted/` — validate against an allow-list, same path-traversal defense as `StaticFileHandler`.

### `DashHandler.java`

Routes:

```text
GET /dash/manifest.mpd
GET /dash/init.m4s
GET /dash/{segment}.m4s
```

- `manifest.mpd` → `Content-Type: application/dash+xml`
- `.m4s` (init and media segments) → `Content-Type: video/mp4` (technically `video/iso.segment`, `video/mp4` is broadly compatible), through the shared range-serving path — fMP4/DASH players routinely issue range requests against segments, so this handler *depends on* Section 4.

### `KeyHandler.java`

```text
GET /hls/encrypted/key
```

- Returns the raw 16-byte AES-128 key with `Content-Type: application/octet-stream`.
- **Protected**: require a header/token (e.g. `X-Stream-Token: <shared-secret>`) before releasing the key; missing/invalid → `401 Unauthorized`.
- Document plainly in the README that this is a stand-in for real entitlement/session auth (signed URLs, short-lived tokens, per-user licensing) — not a production access-control system. That honesty is itself a good interview answer.

### `#EXT-X-KEY` in the encrypted playlist (generated by ffmpeg, not by your code)

```text
#EXT-X-KEY:METHOD=AES-128,URI="/hls/encrypted/key",IV=0x...
#EXTINF:4.0,
seg-000.ts
```

---

## 7. Metrics Endpoint

`MetricsRegistry.java` — thread-safe counters (`AtomicLong`/`LongAdder`) updated by `ClientHandler`/`RequestLogger` on every request:

```text
requests_total
requests_by_status{200,206,404,...}
active_connections
bytes_served_total
uptime_seconds
```

`MetricsHandler.java` exposes `GET /metrics` as either JSON or Prometheus-style plain text:

```text
requests_total 15234
requests_by_status_200 14800
requests_by_status_404 120
active_connections 6
uptime_seconds 842
```

Keep it live (read current counter state on each request) rather than a periodic snapshot — that's the whole value of the endpoint for the benchmark/demo.

---

## 8. Configuration

```java
public class ServerConfig {
    private int port;
    private int workerThreads;
    private String staticDirectory;
    private String mediaDirectory;
    private int keepAliveTimeoutMs;
    private boolean metricsEnabled;
}
```

---

## 9. Browser Player Validation (`resources/public/player/player.html`)

A single static HTML page, served by the existing `StaticFileHandler` — no separate web stack:

- Loads `hls.js` and `dash.js` from CDN.
- Two `<video>` elements (or a dropdown): one pointed at `/hls/clear/playlist.m3u8`, one at `/hls/encrypted/playlist.m3u8` (hls.js will fetch the key from `/hls/encrypted/key` automatically per the playlist's `#EXT-X-KEY`), one at `/dash/manifest.mpd` via dash.js.
- This is a manual validation tool, not part of the automated test suite — use it once per handler to confirm real playback, screenshot it for the README.

---

## 10. Explicitly Out of Scope

```text
WebRTC       — UDP/P2P/SRTP-based, not an HTTP delivery model; documented as future work
Widevine DRM — proprietary, Google-licensed, cannot be self-hosted or reimplemented
HTTP/2, HTTPS/TLS termination
Multi-range requests (bytes=a-b,c-d)
Adaptive bitrate (multiple renditions) — single-rendition streams only
Database, auth beyond the shared-secret key gate, GUI beyond the validation player
```

State the WebRTC/Widevine exclusions in the README with the one-line rationale above — it reads as informed scoping, not a gap.

---

## 11. Testing Strategy

```text
HttpParserTest      — GET, POST, headers, body, malformed request
RouterTest           — known routes, unknown route, wrong method
RangeParserTest      — full range, open-ended range, suffix range, out-of-bounds → 416
StaticFileHandlerTest — existing file → 200, missing → 404, traversal → rejected, 
                         valid Range → 206, invalid Range → 416
```

Manual/integration checks (not unit tests): keep-alive reuse (single TCP connection, multiple requests via curl `--keepalive` or a small script), key endpoint auth (with/without token), player.html playback for all three stream types.

---

## 12. Benchmarking

Two harnesses:

- **`benchmark.py`** — the original core-server benchmark: hammer `/hello`, measure requests/sec and latency percentiles across worker counts.
- **`streaming_benchmark.py`** — simulates a realistic viewer: fetch playlist → fetch key (if encrypted) → fetch N segments in sequence, repeated across concurrent simulated viewers. Measures the same metrics (throughput, avg/p50/p95/p99 latency) but against the streaming workload, which is what actually exercises range handling and keep-alive reuse.

### Configurations to test (minimum)

```text
Workers: 1, 8            (4 and 16 as time allows)
Concurrency: 10, 50, 100  (streaming benchmark: concurrent simulated viewers)
```

### Record

| Workers | Requests/sec | Avg Latency | p95 Latency | p99 Latency |
|---:|---:|---:|---:|---:|
| 1 | Actual result | Actual result | Actual result | Actual result |
| 8 | Actual result | Actual result | Actual result | Actual result |

**Never invent benchmark values — every number in the README and on the resume must come from a saved run.**

Record the environment (CPU, RAM, OS, Java version, worker count, concurrency, endpoint) for reproducibility, same as the base plan.

---

## 13. Three-Day Roadmap — 2 Developers

Split by ownership, not by "one does frontend, one does backend" — both write Java against the same `Handler` interface, defined in the first hour, so nobody blocks on the other.

**Dev A — Core, Concurrency, Ops, Benchmark**
**Dev B — Protocol Features, Streaming, Player, Validation**

### Day 1 — Core server + media prep (parallel from hour one)

**Dev A (morning):** Maven project skeleton, `Main`, `HttpServer`, `ClientHandler` (single-request version first), `HttpRequest`/`HttpResponse`/`HttpParser`/`HttpStatus`. Get `curl localhost:8080/hello` working. Define `Router`/`Route`/`Handler` interface early and share it.

**Dev A (afternoon):** `RootHandler`, `EchoHandler`, 404/405/400/500 wiring, `ServerConfig`.

**Dev B (morning):** Source/obtain a short sample video; write and run `media/prepare-media.sh` to produce clear HLS, AES-128 encrypted HLS, and DASH fixtures via `ffmpeg`. Verify the encrypted playlist actually decrypts (test with `ffplay` or VLC + the raw key) before building any server code around it.

**Dev B (afternoon):** Once `Handler` interface lands, stub `StaticFileHandler` with path-traversal protection, and stub `HlsHandler`/`DashHandler`/`KeyHandler` (routing + correct MIME types, serving full files, no range yet).

**End of Day 1 goal:** core server responds on all base routes; all three media fixture sets exist on disk and play correctly outside the server; streaming handlers serve full (non-range) files correctly.

### Day 2 — Concurrency, Range, Keep-Alive, Metrics

**Dev A (morning):** `ExecutorService` integration (`newFixedThreadPool`), worker-count config, request logging with thread names, graceful shutdown.

**Dev A (afternoon):** `MetricsRegistry` + `MetricsHandler` (`/metrics`), wired into `ClientHandler` so every request updates counters.

**Dev B (morning):** `RangeParser` + shared range-serving logic; wire into `StaticFileHandler`, `HlsHandler`, `DashHandler`. Test with `curl -H "Range: bytes=0-999"` against a segment.

**Dev B (afternoon):** `KeyHandler` auth check (401 on missing/bad token), keep-alive loop in `ClientHandler` (pair with Dev A since it touches the same class — do this as a short joint session), `player.html` with hls.js + dash.js pointed at all three stream types.

**End of Day 2 goal:** genuinely concurrent, keep-alive-capable server; seeking works (206) on all media endpoints; encrypted HLS plays in the browser player; `/metrics` reports live counts.

### Day 3 — Benchmark, integration, README, resume bullets

**Morning (both):** Integration pass — run through Definition of Done together, fix breakage. Dev A runs `benchmark.py` across 1/8 (and 4/16 if time allows) workers and saves results. Dev B runs `streaming_benchmark.py` against the same worker configs.

**Afternoon (split):** Dev A writes the benchmark section of the README (table + explanation of why throughput changes with worker count, the keep-alive/thread-pool tradeoff). Dev B writes the streaming architecture section (HLS/DASH/AES-128 flow, WebRTC/Widevine exclusion rationale, screenshots from `player.html`).

**Evening (both):** Merge README, push clean repo, draft resume bullets together from Section 14 below.

---

## 14. Resume Target

### Point 1 — Implementation

Multithreaded HTTP/1.1 server built from raw TCP sockets in Java (no framework), with a custom parser, router, and fixed thread-pool concurrency model, extended into a multi-protocol streaming origin (HLS + MPEG-DASH) with AES-128 encrypted delivery, HTTP Range support, and persistent keep-alive connections.

### Point 2 — Performance (from actual benchmark data only)

Report measured throughput and p95/p99 latency across 1/4/8/16 worker configurations, for both the base HTTP workload and the realistic playlist+segment streaming workload.

### Point 3 — Engineering

Pick concrete, true features: path-traversal-protected static/media serving, AES-128 key-gated HLS delivery, Range-based partial content for seeking, keep-alive with idle timeout, a live `/metrics` endpoint, or the deliberate WebRTC/Widevine scoping decision documented in the README.

**Do not decide the numbers before running the benchmark.**

---

## 15. Definition of Done

- [ ] Server starts, accepts TCP connections, parses HTTP requests, generates responses
- [ ] GET/POST work; headers and bodies handled
- [ ] Routing works; 404/405/400/500 correct
- [ ] Static files served; path traversal blocked
- [ ] Range requests return 206/416 correctly on static and media files
- [ ] Keep-alive reuses a single TCP connection across multiple requests; idle timeout works
- [ ] Thread pool processes concurrent clients; thread names visible in logs
- [ ] Graceful shutdown works
- [ ] HLS clear stream plays in `player.html`
- [ ] HLS encrypted stream plays in `player.html`; key endpoint rejects unauthorized requests
- [ ] DASH stream plays in `player.html`
- [ ] `/metrics` returns live, accurate counts
- [ ] Parser/Router/Range/StaticFile unit tests pass
- [ ] `benchmark.py` and `streaming_benchmark.py` both run and produce saved results
- [ ] At least 2 worker configurations benchmarked with real numbers (1 and 8 minimum)
- [ ] README complete: architecture, how to run, API + streaming examples, concurrency model, keep-alive tradeoff, benchmark methodology + results, WebRTC/Widevine scoping rationale, limitations, future work
- [ ] GitHub repo clean; resume bullets based only on measured results

## Priority

**Working core → concurrency/range/keep-alive → streaming handlers + encryption → benchmark → README/polish.**

If time runs short on Day 3, cut worker-config breadth (drop 4/16, keep 1/8) before cutting the write-up — the numbers only matter if someone can read what they mean.
