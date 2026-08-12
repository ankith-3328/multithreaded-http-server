package com.ankith.httpserver.http;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public record HttpResponse(
        HttpStatus status,
        Map<String, String> headers,
        byte[] body
) {
    public HttpResponse {
        headers = new LinkedHashMap<>(headers);
        body = body == null ? new byte[0] : body;

        headers.putIfAbsent(
                "Content-Length",
                String.valueOf(body.length)
        );
    }

    public static HttpResponse text(HttpStatus status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/plain; charset=utf-8");

        return new HttpResponse(status, headers, bytes);
    }

    public static HttpResponse empty(HttpStatus status) {
        return new HttpResponse(status, Map.of(), new byte[0]);
    }
}