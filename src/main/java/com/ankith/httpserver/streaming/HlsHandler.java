package com.ankith.httpserver.streaming;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;
import com.ankith.httpserver.http.HttpStatus;
import com.ankith.httpserver.router.Handler;

import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Serves HLS VOD playlists and segments:
 *
 *   GET /hls/clear/playlist.m3u8
 *   GET /hls/clear/seg-000.ts
 *   GET /hls/encrypted/playlist.m3u8
 *   GET /hls/encrypted/seg-000.ts
 *
 * Byte ranges are delegated to the shared FileRangeService.
 *
 * Security (design doc section 48): stream.key must never be reachable
 * as /hls/encrypted/stream.key, so filenames are restricted to a
 * whitelist. The key is exposed exclusively through KeyHandler.
 */
public final class HlsHandler implements Handler {

    private static final Set<String> STREAMS =
            Set.of("clear", "encrypted");

    private static final Pattern ALLOWED_FILE =
            Pattern.compile("playlist\\.m3u8|seg-\\d+\\.ts");

    private final Path mediaRoot;
    private final FileRangeService rangeService;

    public HlsHandler(Path mediaRoot, FileRangeService rangeService) {
        this.mediaRoot = mediaRoot.toAbsolutePath().normalize();
        this.rangeService = rangeService;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        String prefix = "/hls/";
        String path = request.target();

        if (!path.startsWith(prefix)) {
            return HttpResponse.text(HttpStatus.NOT_FOUND, "Not Found\n");
        }

        String relative = path.substring(prefix.length());
        String[] parts = relative.split("/");

        if (parts.length != 2 || !STREAMS.contains(parts[0])) {
            return HttpResponse.text(HttpStatus.NOT_FOUND, "Not Found\n");
        }

        String stream = parts[0];
        String fileName = parts[1];

        if (!isSafeFileName(fileName)
                || !ALLOWED_FILE.matcher(fileName).matches()) {
            return HttpResponse.text(HttpStatus.BAD_REQUEST, "Invalid path\n");
        }

        Path root = mediaRoot
                .resolve(stream.equals("clear") ? "hls-clear" : "hls-encrypted")
                .normalize();

        Path file = root.resolve(fileName).normalize();

        if (!file.startsWith(root)) {
            return HttpResponse.text(HttpStatus.BAD_REQUEST, "Invalid path\n");
        }

        return rangeService.serve(
                file,
                MimeTypes.forPath(fileName),
                request.header("range")
        );
    }

    private boolean isSafeFileName(String name) {
        return !name.contains("..")
                && !name.contains("/")
                && !name.contains("\\");
    }
}
