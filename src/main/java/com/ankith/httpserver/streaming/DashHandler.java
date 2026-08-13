package com.ankith.httpserver.streaming;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;
import com.ankith.httpserver.http.HttpStatus;
import com.ankith.httpserver.router.Handler;

import java.nio.file.Path;

/**
 * Serves DASH VOD files:
 *
 *   GET /dash/manifest.mpd
 *   GET /dash/init-0.m4s
 *   GET /dash/seg-0-001.m4s
 *
 * Byte ranges are delegated to the shared FileRangeService.
 */
public final class DashHandler implements Handler {

    private final Path dashRoot;
    private final FileRangeService rangeService;

    public DashHandler(Path dashRoot, FileRangeService rangeService) {
        this.dashRoot = dashRoot.toAbsolutePath().normalize();
        this.rangeService = rangeService;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {

        String prefix = "/dash/";

        if (!request.target().startsWith(prefix)) {
            return HttpResponse.text(HttpStatus.NOT_FOUND, "Not Found\n");
        }

        String fileName = request.target().substring(prefix.length());

        if (!isSafe(fileName)) {
            return HttpResponse.text(HttpStatus.BAD_REQUEST, "Invalid path\n");
        }

        Path file = dashRoot.resolve(fileName).normalize();

        if (!file.startsWith(dashRoot)) {
            return HttpResponse.text(HttpStatus.BAD_REQUEST, "Invalid path\n");
        }

        return rangeService.serve(
                file,
                MimeTypes.forPath(fileName),
                request.header("range")
        );
    }

    private boolean isSafe(String fileName) {
        return !fileName.contains("..")
                && !fileName.contains("/")
                && !fileName.contains("\\");
    }
}
