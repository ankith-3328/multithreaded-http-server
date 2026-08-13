package com.ankith.httpserver.handler;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;
import com.ankith.httpserver.http.HttpStatus;
import com.ankith.httpserver.router.Handler;

import java.nio.charset.StandardCharsets;

/**
 * Echoes back method, target, headers, and body.
 * Useful for manually testing POST + Content-Length handling.
 */
public final class EchoHandler implements Handler {

    @Override
    public HttpResponse handle(HttpRequest request) {
        StringBuilder body = new StringBuilder();

        body.append("Method: ").append(request.method()).append("\n");
        body.append("Target: ").append(request.target()).append("\n");
        body.append("Headers:\n");

        request.headers().forEach((k, v) ->
                body.append("  ").append(k).append(": ").append(v).append("\n"));

        body.append("Body (" ).append(request.body().length).append(" bytes):\n");
        body.append(new String(request.body(), StandardCharsets.UTF_8));

        return HttpResponse.text(HttpStatus.OK, body.toString());
    }
}
