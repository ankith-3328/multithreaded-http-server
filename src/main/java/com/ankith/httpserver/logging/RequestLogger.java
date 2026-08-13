package com.ankith.httpserver.logging;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;

public final class RequestLogger {

    private RequestLogger() {}

    public static void log(HttpRequest request, HttpResponse response) {
        String thread = Thread.currentThread().getName();

        System.out.printf(
                "[%s] %s %s -> %d (%d bytes)%n",
                thread,
                request.method(),
                request.target(),
                response.status().code(),
                response.body().length
        );
    }
}
