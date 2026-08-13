package com.ankith.httpserver.streaming;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;
import com.ankith.httpserver.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Handler-level streaming tests (no sockets, no real media).
 * Fixtures are synthetic files in a temp dir mirroring media/ layout.
 * End-to-end socket/browser validation happens in integration tests.
 */
class StreamingHandlerTest {

    private static final String TOKEN = "dev-secret";

    @TempDir
    Path mediaRoot;

    private HlsHandler hlsHandler;
    private DashHandler dashHandler;
    private KeyHandler keyHandler;

    private byte[] clearSegment;
    private byte[] keyBytes;

    @BeforeEach
    void setUp() throws Exception {
        Path clear = Files.createDirectories(mediaRoot.resolve("hls-clear"));
        Path encrypted = Files.createDirectories(mediaRoot.resolve("hls-encrypted"));
        Path dash = Files.createDirectories(mediaRoot.resolve("dash"));

        Files.writeString(clear.resolve("playlist.m3u8"),
                "#EXTM3U\n#EXT-X-TARGETDURATION:4\n"
                        + "#EXTINF:4.0,\nseg-000.ts\n#EXT-X-ENDLIST\n");

        clearSegment = new byte[1000];
        for (int i = 0; i < clearSegment.length; i++) {
            clearSegment[i] = (byte) (i % 251);
        }
        Files.write(clear.resolve("seg-000.ts"), clearSegment);

        Files.writeString(encrypted.resolve("playlist.m3u8"),
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,"
                        + "URI=\"/hls/encrypted/key\",IV=0x00\n"
                        + "#EXTINF:4.0,\nseg-000.ts\n#EXT-X-ENDLIST\n");
        Files.write(encrypted.resolve("seg-000.ts"), new byte[500]);

        keyBytes = new byte[16];
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] = (byte) (i + 1);
        }
        Files.write(encrypted.resolve("stream.key"), keyBytes);

        Files.writeString(dash.resolve("manifest.mpd"),
                "<?xml version=\"1.0\"?><MPD type=\"static\"/>");
        Files.write(dash.resolve("seg-0-001.m4s"), new byte[2048]);

        FileRangeService rangeService = new FileRangeService();
        hlsHandler = new HlsHandler(mediaRoot, rangeService);
        dashHandler = new DashHandler(dash, rangeService);
        keyHandler = new KeyHandler(encrypted.resolve("stream.key"), TOKEN);
    }

    private static HttpRequest get(String target) {
        return get(target, Map.of());
    }

    private static HttpRequest get(String target, Map<String, String> headers) {
        return new HttpRequest("GET", target, "HTTP/1.1", headers, new byte[0]);
    }

    private static String body(HttpResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    // ---------- HLS clear ----------

    @Test
    void clearPlaylistReturns200WithHlsMimeType() {
        HttpResponse r = hlsHandler.handle(get("/hls/clear/playlist.m3u8"));

        assertEquals(HttpStatus.OK, r.status());
        assertEquals("application/vnd.apple.mpegurl", r.headers().get("Content-Type"));
        assertTrue(body(r).contains("#EXTM3U"));
        assertEquals(String.valueOf(r.body().length), r.headers().get("Content-Length"));
    }

    @Test
    void clearSegmentReturns200WithTsMimeType() {
        HttpResponse r = hlsHandler.handle(get("/hls/clear/seg-000.ts"));

        assertEquals(HttpStatus.OK, r.status());
        assertEquals("video/mp2t", r.headers().get("Content-Type"));
        assertEquals("bytes", r.headers().get("Accept-Ranges"));
        assertArrayEquals(clearSegment, r.body());
    }

    @Test
    void segmentRangeReturns206WithContentRange() {
        HttpResponse r = hlsHandler.handle(
                get("/hls/clear/seg-000.ts", Map.of("range", "bytes=100-199")));

        assertEquals(HttpStatus.PARTIAL_CONTENT, r.status());
        assertEquals("bytes 100-199/1000", r.headers().get("Content-Range"));
        assertEquals("100", r.headers().get("Content-Length"));
        assertArrayEquals(Arrays.copyOfRange(clearSegment, 100, 200), r.body());
    }

    @Test
    void unsatisfiableRangeReturns416() {
        HttpResponse r = hlsHandler.handle(
                get("/hls/clear/seg-000.ts", Map.of("range", "bytes=5000-")));

        assertEquals(HttpStatus.RANGE_NOT_SATISFIABLE, r.status());
        assertEquals("bytes */1000", r.headers().get("Content-Range"));
        assertEquals(0, r.body().length);
    }

    @Test
    void unknownStreamReturns404() {
        HttpResponse r = hlsHandler.handle(get("/hls/unknown/seg-000.ts"));
        assertEquals(HttpStatus.NOT_FOUND, r.status());
    }

    @Test
    void missingFileReturns404() {
        HttpResponse r = hlsHandler.handle(get("/hls/clear/seg-999.ts"));
        assertEquals(HttpStatus.NOT_FOUND, r.status());
    }

    @Test
    void malformedPathReturns404() {
        assertEquals(HttpStatus.NOT_FOUND,
                hlsHandler.handle(get("/hls/clear")).status());
        assertEquals(HttpStatus.NOT_FOUND,
                hlsHandler.handle(get("/hls/clear/a/b")).status());
    }

    @Test
    void pathTraversalIsRejected() {
        assertEquals(HttpStatus.BAD_REQUEST,
                hlsHandler.handle(get("/hls/clear/..")).status());
        assertEquals(HttpStatus.NOT_FOUND,
                hlsHandler.handle(get("/hls/clear/../hls-encrypted/stream.key")).status());
    }

    @Test
    void streamKeyIsNeverServedByHlsHandler() {
        // Design section 48: key reachable ONLY through KeyHandler.
        HttpResponse r = hlsHandler.handle(get("/hls/encrypted/stream.key"));

        assertEquals(HttpStatus.BAD_REQUEST, r.status());
        assertFalse(Arrays.equals(keyBytes, r.body()));
    }

    // ---------- HLS encrypted ----------

    @Test
    void encryptedPlaylistReturns200AndReferencesKeyEndpoint() {
        HttpResponse r = hlsHandler.handle(get("/hls/encrypted/playlist.m3u8"));

        assertEquals(HttpStatus.OK, r.status());
        assertEquals("application/vnd.apple.mpegurl", r.headers().get("Content-Type"));
        assertTrue(body(r).contains("#EXT-X-KEY"));
        assertTrue(body(r).contains("/hls/encrypted/key"));
    }

    @Test
    void encryptedSegmentReturns200() {
        HttpResponse r = hlsHandler.handle(get("/hls/encrypted/seg-000.ts"));

        assertEquals(HttpStatus.OK, r.status());
        assertEquals("video/mp2t", r.headers().get("Content-Type"));
        assertEquals(500, r.body().length);
    }

    // ---------- Key endpoint ----------

    @Test
    void keyWithoutTokenReturns401() {
        HttpResponse r = keyHandler.handle(get("/hls/encrypted/key"));

        assertEquals(HttpStatus.UNAUTHORIZED, r.status());
        assertEquals(0, r.body().length);
    }

    @Test
    void keyWithWrongTokenReturns401() {
        HttpResponse r = keyHandler.handle(
                get("/hls/encrypted/key", Map.of("x-stream-token", "wrong")));

        assertEquals(HttpStatus.UNAUTHORIZED, r.status());
        assertEquals(0, r.body().length);
    }

    @Test
    void keyWithValidTokenReturns16ByteKey() {
        HttpResponse r = keyHandler.handle(
                get("/hls/encrypted/key", Map.of("x-stream-token", TOKEN)));

        assertEquals(HttpStatus.OK, r.status());
        assertEquals("application/octet-stream", r.headers().get("Content-Type"));
        assertEquals("no-store", r.headers().get("Cache-Control"));
        assertEquals("16", r.headers().get("Content-Length"));
        assertArrayEquals(keyBytes, r.body());
    }

    @Test
    void keyRejectsNonGetMethods() {
        HttpResponse r = keyHandler.handle(
                new HttpRequest("POST", "/hls/encrypted/key", "HTTP/1.1",
                        Map.of("x-stream-token", TOKEN), new byte[0]));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, r.status());
    }

    // ---------- DASH ----------

    @Test
    void dashManifestReturns200WithDashMimeType() {
        HttpResponse r = dashHandler.handle(get("/dash/manifest.mpd"));

        assertEquals(HttpStatus.OK, r.status());
        assertEquals("application/dash+xml", r.headers().get("Content-Type"));
        assertTrue(body(r).contains("<MPD"));
    }

    @Test
    void dashSegmentReturns200WithMp4MimeType() {
        HttpResponse r = dashHandler.handle(get("/dash/seg-0-001.m4s"));

        assertEquals(HttpStatus.OK, r.status());
        assertEquals("video/mp4", r.headers().get("Content-Type"));
        assertEquals(2048, r.body().length);
    }

    @Test
    void dashSegmentRangeReturns206() {
        HttpResponse r = dashHandler.handle(
                get("/dash/seg-0-001.m4s", Map.of("range", "bytes=0-999")));

        assertEquals(HttpStatus.PARTIAL_CONTENT, r.status());
        assertEquals("bytes 0-999/2048", r.headers().get("Content-Range"));
        assertEquals(1000, r.body().length);
    }

    @Test
    void dashTraversalIsRejected() {
        assertEquals(HttpStatus.BAD_REQUEST,
                dashHandler.handle(get("/dash/../hls-encrypted/stream.key")).status());
    }

    @Test
    void dashMissingFileReturns404() {
        assertEquals(HttpStatus.NOT_FOUND,
                dashHandler.handle(get("/dash/nope.m4s")).status());
    }

    // ---------- MIME types ----------

    @Test
    void mimeTypeMappings() {
        assertEquals("application/vnd.apple.mpegurl", MimeTypes.forPath("a.m3u8"));
        assertEquals("video/mp2t", MimeTypes.forPath("a.ts"));
        assertEquals("application/dash+xml", MimeTypes.forPath("a.mpd"));
        assertEquals("video/mp4", MimeTypes.forPath("a.m4s"));
        assertEquals("text/html; charset=utf-8", MimeTypes.forPath("a.html"));
        assertEquals("application/octet-stream", MimeTypes.forPath("a.bin"));
    }
}
