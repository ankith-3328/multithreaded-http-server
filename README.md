# Multithreaded HTTP/1.1 Server + HLS/DASH Streaming Origin

A multithreaded HTTP/1.1 server built from raw TCP sockets in **Java 21** —
no HTTP or server framework — extended into a small video-on-demand
streaming origin serving **HLS** (clear and AES-128 encrypted) and
**MPEG-DASH**, with byte-range requests, persistent keep-alive
connections, a fixed worker thread pool, and a live metrics endpoint.

Media is **not** transcoded or packaged by the server. FFmpeg generates
the streaming fixtures offline (`media/prepare-media.sh`); the server's
job is to deliver the generated files correctly and efficiently.

## Features

- HTTP/1.1 request parsing and response serialization (raw sockets)
- GET / POST routing
- Static file serving with path-traversal protection
- HTTP Range requests: `200`, `206`, `416`, `Accept-Ranges`, `Content-Range`
- Persistent HTTP/1.1 keep-alive with idle timeout
- Fixed `ExecutorService` worker pool (configurable size)
- HLS VOD: clear (`.m3u8` + `.ts`) and AES-128 encrypted, with an
  authenticated key endpoint
- MPEG-DASH VOD (`.mpd` + `.m4s`)
- Live `/metrics` endpoint
- JUnit 5 test suite + GitHub Actions CI
- Reproducible async benchmark harness

## Architecture

```
                         Browser / curl / benchmark
                                   |
                                   v
                          +----------------+
                          |  ServerSocket  |   accept loop (sequential)
                          +-------+--------+
                                  |
                          +-------v----------------+
                          |  Fixed ExecutorService |   workerThreads (default 8)
                          +-------+----------------+
                                  |
                          +-------v---------+
                          |  ClientHandler  |   keep-alive request loop
                          +-------+---------+
                                  |
                          +-------v---------+
                          |   HttpParser    |   syntax, limits, Content-Length
                          +-------+---------+
                                  |
                          +-------v---------+
                          |     Router      |   prefix, first-match
                          +--+-----+-----+--+
                             |     |     |
                +------------+     |     +-------------+
                v                  v                   v
           /hello /echo      /hls/...  /dash/...   /metrics /player/ /
                             |
                  +----------+----------+
                  |          |          |
              playlist    segment      key        <- key only via KeyHandler
                  |          |
                  +---- FileRangeService ----+    <- shared Range logic
                                  |
                             File System
```

Responsibilities are deliberately separated:

| Class | Responsibility |
|---|---|
| `ClientHandler` | TCP connection lifecycle, keep-alive loop |
| `HttpParser` | HTTP request syntax, size limits |
| `Router` / `Route` | route selection (prefix, first match) |
| `Handler` (interface) | application semantics; returns `HttpResponse`, never touches sockets |
| `FileRangeService` | full-file and byte-range serving (shared by HLS, DASH, static) |
| `HttpResponse` / `ResponseWriter` | response representation / bytes on the socket |

## Project Structure

```
.
├── pom.xml                          Maven build (Java 21, JUnit 5)
├── README.md
├── DESIGN_HLS_2_DEVELOPERS.md       full design document
├── .gitignore
├── .github/
│   └── workflows/
│       └── ci.yml                   mvn test on PRs to main
├── src/
│   ├── main/
│   │   └── java/com/ankith/httpserver/
│   │       ├── Main.java            route wiring + startup + shutdown hook
│   │       ├── config/
│   │       │   └── ServerConfig.java
│   │       ├── server/
│   │       │   ├── HttpServer.java          ServerSocket + fixed ExecutorService
│   │       │   ├── ClientHandler.java       keep-alive request loop per connection
│   │       │   └── KeepAliveManager.java
│   │       ├── http/
│   │       │   ├── HttpParser.java          request line, headers, Content-Length body
│   │       │   ├── HttpRequest.java
│   │       │   ├── HttpResponse.java
│   │       │   ├── HttpStatus.java
│   │       │   ├── ResponseWriter.java
│   │       │   ├── Range.java
│   │       │   └── RangeParser.java         bytes=start-end / start- / -suffix
│   │       ├── router/
│   │       │   ├── Handler.java             handle(HttpRequest) -> HttpResponse
│   │       │   ├── Route.java
│   │       │   └── Router.java              prefix, first match wins
│   │       ├── handler/
│   │       │   ├── RootHandler.java
│   │       │   ├── HelloHandler.java
│   │       │   ├── EchoHandler.java
│   │       │   └── StaticFileHandler.java
│   │       ├── streaming/
│   │       │   ├── HlsHandler.java          /hls/clear/*, /hls/encrypted/*
│   │       │   ├── KeyHandler.java          /hls/encrypted/key (token auth)
│   │       │   ├── DashHandler.java         /dash/*
│   │       │   ├── FileRangeService.java    shared full-file + range serving
│   │       │   └── MimeTypes.java
│   │       ├── metrics/
│   │       │   ├── MetricsRegistry.java     lock-free counters
│   │       │   └── MetricsHandler.java      GET /metrics
│   │       └── logging/
│   │           └── RequestLogger.java
│   └── test/java/com/ankith/httpserver/
│       ├── http/                 HttpParserTest, HttpResponseTest,
│       │                         ResponseWriterTest, RangeParserTest
│       ├── router/               RouterTest
│       ├── handler/              StaticFileHandlerTest
│       └── streaming/            StreamingHandlerTest (HLS/DASH/key)
├── media/
│   ├── prepare-media.sh          ffmpeg fixture generator
│   ├── enc.keyinfo               ffmpeg key-info file (local fixture)
│   ├── source/
│   │   └── sample.mp4            1080p H.264 + AAC source (manual, one-time)
│   ├── hls-clear/                playlist.m3u8 + seg-000.ts ... (54 files)
│   ├── hls-encrypted/            playlist.m3u8 + stream.key + segments (55 files)
│   └── dash/                     manifest.mpd + init-*.m4s + seg-*.m4s (96 files)
└── benchmark/
    ├── benchmark.py              async load generator (rps, avg, p50/p95/p99)
    └── results/                  recorded JSON runs (workers 1/4/8/16)
```

## Requirements

- JDK 21
- Maven 3.x
- FFmpeg + OpenSSL (only for regenerating media fixtures)
- Python 3 with `httpx` (only for the benchmark harness)

## Build & Run

```bash
mvn package
java -cp target/classes com.ankith.httpserver.Main
```

The server listens on the port in `ServerConfig.defaults()` (default
**8080**). Stop with `Ctrl+C` — a shutdown hook closes the listen socket
and shuts the worker pool down.

## Configuration

All configuration lives in `config/ServerConfig` — nothing is hard-coded
in handlers.

| Field | Default | Meaning |
|---|---|---|
| `port` | `8080` | listen port |
| `workerThreads` | `8` | fixed pool size |
| `staticDirectory` | `src/main/resources/public` | static file root |
| `mediaDirectory` | `media` | streaming fixture root |
| `keepAliveTimeoutMs` | `10_000` | idle keep-alive timeout |
| `metricsEnabled` | `true` | metrics collection |
| `streamToken` | `dev-secret` | shared dev token for the key endpoint |

## Routes

| Method | Path | Description | Auth |
|---|---|---|---|
| GET | `/hello` | sanity endpoint | – |
| POST | `/echo` | echoes method, headers, body | – |
| GET | `/hls/clear/playlist.m3u8` | clear HLS playlist | – |
| GET | `/hls/clear/seg-NNN.ts` | clear HLS segment (Range supported) | – |
| GET | `/hls/encrypted/playlist.m3u8` | encrypted HLS playlist | – |
| GET | `/hls/encrypted/seg-NNN.ts` | encrypted HLS segment (Range supported) | – |
| GET | `/hls/encrypted/key` | AES-128 key (16 bytes) | `X-Stream-Token` |
| GET | `/dash/manifest.mpd` | DASH manifest | – |
| GET | `/dash/*.m4s` | DASH init/media segments (Range supported) | – |
| GET | `/metrics` | live counters (text) | – |
| GET | `/player/*` | static files (browser player page lands here) | – |

The router is **prefix-based, first match wins** — so `/hls/encrypted/key`
is registered before `/hls/` to avoid being shadowed by the HLS handler.

### Examples

```bash
# clear playlist
curl -i http://localhost:8080/hls/clear/playlist.m3u8

# byte range of a segment -> 206
curl -i -H "Range: bytes=0-999" http://localhost:8080/hls/clear/seg-000.ts

# unsatisfiable range -> 416 with Content-Range: bytes */SIZE
curl -i -H "Range: bytes=99999999-" http://localhost:8080/hls/clear/seg-000.ts

# key endpoint: unauthorized
curl -i http://localhost:8080/hls/encrypted/key            # 401

# key endpoint: authorized -> 200, 16 bytes, Cache-Control: no-store
curl -i -H "X-Stream-Token: dev-secret" http://localhost:8080/hls/encrypted/key

# stream.key is NOT reachable through the HLS route -> 400
curl -i http://localhost:8080/hls/encrypted/stream.key

# metrics
curl http://localhost:8080/metrics
```

## Media Preparation

```bash
# place a 1080p H.264 + AAC MP4 at media/source/sample.mp4 (one-time, manual)
bash media/prepare-media.sh
```

This regenerates:

```
media/hls-clear/       playlist.m3u8 + seg-NNN.ts           (clear HLS)
media/hls-encrypted/   playlist.m3u8 + seg-NNN.ts + stream.key (AES-128 HLS)
media/dash/            manifest.mpd + init-N.m4s + seg-N-MMM.m4s (DASH)
```

Verify fixtures independently before testing the server
(`ffplay media/hls-clear/playlist.m3u8`).

## Streaming Flows

### Clear HLS

```
GET /hls/clear/playlist.m3u8  -> 200 application/vnd.apple.mpegurl
GET /hls/clear/seg-000.ts     -> 200 video/mp2t (or 206 with Range)
```

### AES-128 encrypted HLS

```
playlist.m3u8 contains:  #EXT-X-KEY:METHOD=AES-128,URI="/hls/encrypted/key",IV=0x...
        |
        v
GET /hls/encrypted/key  +  X-Stream-Token: dev-secret
        |
  token invalid/missing -> 401 (empty body)
  token valid           -> 200, 16-byte key, Cache-Control: no-store
        |
        v
player fetches encrypted segments and decrypts locally
```

Note: the `X-Stream-Token` header is not sent automatically by hls.js —
the player must inject it (e.g. via `xhrSetup`). The token is a
project-level shared secret for local development; **this is not DRM and
not production key management**.

### DASH

```
GET /dash/manifest.mpd      -> 200 application/dash+xml
GET /dash/init-0.m4s        -> 200 video/mp4
GET /dash/seg-0-001.m4s     -> 200 / 206 video/mp4
```

## Range Requests

Single byte ranges, per RFC 7233 subset:

```
bytes=100-199   -> bytes 100..199
bytes=100-      -> 100..end
bytes=-500      -> last 500 bytes
```

- Valid range → `206 Partial Content` + `Content-Range: bytes start-end/size`
- Unsatisfiable → `416` + `Content-Range: bytes */size`
- Every file response advertises `Accept-Ranges: bytes`
- Multi-range requests are rejected (out of scope)
- Implemented once in `FileRangeService`, shared by HLS, DASH and static

## Concurrency & Keep-Alive

- One acceptor thread; each accepted socket is handed to a fixed pool
  (`workerThreads`, default 8).
- A keep-alive connection stays on its worker thread until the client
  sends `Connection: close` or the idle timeout
  (`keepAliveTimeoutMs`) fires.
- HTTP/1.1 defaults to keep-alive; every response carries
  `Content-Length` so clients can frame responses without disconnecting.
- Trade-off: a keep-alive connection occupies one worker for its whole
  lifetime, so `workerThreads` caps truly concurrent clients — simple
  and predictable, and it makes worker-count benchmarking meaningful.

## Metrics

`GET /metrics` returns plain-text counters:

```
requests_total 15234
requests_by_status_200 14800
requests_by_status_206 300
requests_by_status_404 120
active_connections 6
bytes_served_total 92839203
uptime_seconds 842
```

Counters are recorded centrally in `ClientHandler` using lock-free
`LongAdder`s, safe under the worker pool.

## Testing

```bash
mvn test
```

49 tests (all passing) cover: HTTP parser (valid/malformed/oversized),
response writer, router matching, range parser (all range forms +
rejections), static file handler (incl. traversal), and the streaming
handlers (200/MIME, 206/416, unknown stream 404, traversal 400,
`stream.key` blocked, key auth 401/200/405, 16-byte key body).
Streaming handler tests use synthetic `@TempDir` fixtures — no sockets
or real media needed.

CI runs `mvn test` on every pull request to `main`
(`.github/workflows/ci.yml`, Temurin JDK 21).

## Benchmarking

`benchmark/benchmark.py` is an async load generator: N concurrent
clients hammer one endpoint for a fixed duration, every request is timed
individually, and statistics (requests/sec, avg, p50, p95, p99) are
computed from the full latency list and saved to JSON.

```bash
pip install httpx

# 1. configure worker count in ServerConfig, restart the server, then:
python3 benchmark/benchmark.py --url http://localhost:8080/hello \
    --concurrency 8 --duration 10 --workers-label 8 \
    --output-dir benchmark/results
```

`--workers-label` is metadata only (which server configuration the run
was against); `--concurrency` controls the clients this script
generates. See `--help` for all options.

### Recorded results

Real recorded runs against `/hello`, concurrency 8, 10 s per run
(raw JSON in `benchmark/results/`):

| Server workers | Requests | Req/sec | Failed | avg (ms) | p95 (ms) | p99 (ms) |
|---|---|---|---|---|---|---|
| 1  | 226  | 21.77  | 7 | 45.6 | 50.0 | 50.5 |
| 4  | 889  | 88.03  | 4 | 45.1 | 48.9 | 50.3 |
| 8  | 1731 | 172.46 | 0 | 46.1 | 51.7 | 54.9 |
| 16 | 1748 | 174.02 | 0 | 45.7 | 51.6 | 54.2 |

Throughput scales roughly linearly up to 8 workers — the number of
concurrent benchmark clients — and flattens beyond it, as expected when
clients saturate before workers do. Absolute latencies reflect the
development machine; rerun locally for current numbers. A separate
streaming benchmark (playlist → key → segments viewer simulation) is
planned work.

## Security Notes

- All filesystem paths are canonicalized and verified to stay under
  their root (`..` traversal → `400`).
- HLS filenames are whitelisted to `playlist.m3u8` / `seg-N.ts`, so
  `media/hls-encrypted/stream.key` is **only** reachable through the
  authenticated key endpoint.
- Key endpoint compares tokens in constant time and answers `401` with
  an empty body before touching disk.
- Parser limits: max header line 8 KB, max 100 headers, max body 10 MB.
- Do not commit real secrets; `dev-secret` and `stream.key` are local
  fixtures only.

## Limitations & Non-Goals

Current limitations:

- `FileRangeService` buffers whole files in memory
  (`Files.readAllBytes`); streaming the selected byte region directly to
  the socket is a documented follow-up.
- Query strings are not stripped from request targets.
- The catch-all `/` route answers unmatched GET paths with the root
  banner (200) rather than 404.
- Single rendition only (no adaptive bitrate).
- No HTTPS/TLS termination.

Explicit non-goals for this project: WebRTC, DRM (Widevine/FairPlay/
PlayReady), HTTP/2, multi-range responses, live encoding, transcoding,
databases, production auth, CDN, dashboards.

## License

Educational project; use freely.
