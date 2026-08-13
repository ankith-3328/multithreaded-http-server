package com.ankith.httpserver.handler;

import com.ankith.httpserver.http.*;
import com.ankith.httpserver.router.Handler;
import com.ankith.httpserver.streaming.FileRangeService;
import com.ankith.httpserver.streaming.MimeTypes;

import java.nio.file.Path;

public final class StaticFileHandler implements Handler {

    private final Path root;
    private final FileRangeService rangeService;

    public StaticFileHandler(Path root, FileRangeService rangeService) {
        this.root = root.toAbsolutePath().normalize();
        this.rangeService = rangeService;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {

        String relative = request.target().substring(1);

        Path file = root.resolve(relative).normalize();

        if (!file.startsWith(root)) {
            return HttpResponse.text(HttpStatus.BAD_REQUEST, "Invalid path\n");
        }

        return rangeService.serve(
                file,
                MimeTypes.forPath(file.toString()),
                request.header("range")
        );
    }
}
