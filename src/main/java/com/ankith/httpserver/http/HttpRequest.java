package com.ankith.httpserver.http;

import java.util.Map;

public record HttpRequest(
        String method,
        String target,
        String version,
        Map<String, String> headers,
        byte[] body
) {
    public String header(String name) {
        return headers.get(name.toLowerCase());
    }
}