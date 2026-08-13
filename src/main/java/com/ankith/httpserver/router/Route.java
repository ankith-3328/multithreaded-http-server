package com.ankith.httpserver.router;

import com.ankith.httpserver.http.HttpRequest;

public record Route(
        String method,
        String prefix,
        Handler handler
) {
    public boolean matches(HttpRequest request) {
        return method.equalsIgnoreCase(request.method())
                && request.target().startsWith(prefix);
    }

    public boolean pathMatches(HttpRequest request) {
        return request.target().startsWith(prefix);
    }
}