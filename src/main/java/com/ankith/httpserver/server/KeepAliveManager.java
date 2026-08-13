package com.ankith.httpserver.server;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;

import java.util.LinkedHashMap;
import java.util.Map;

public final class KeepAliveManager {

    private KeepAliveManager() {}

    public static boolean shouldKeepAlive(HttpRequest request) {
        String connection = request.header("connection");

        return connection == null
                || !connection.equalsIgnoreCase("close");
    }

    public static HttpResponse withConnectionHeader(
            HttpResponse response,
            boolean keepAlive
    ) {
        Map<String, String> headers =
                new LinkedHashMap<>(response.headers());

        headers.put(
                "Connection",
                keepAlive ? "keep-alive" : "close"
        );

        return new HttpResponse(
                response.status(),
                headers,
                response.body()
        );
    }
}