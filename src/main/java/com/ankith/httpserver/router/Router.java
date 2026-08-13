package com.ankith.httpserver.router;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;
import com.ankith.httpserver.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

public final class Router {

    private final List<Route> routes = new ArrayList<>();

    public void get(String prefix, Handler handler) {
        routes.add(new Route("GET", prefix, handler));
    }

    public void post(String prefix, Handler handler) {
        routes.add(new Route("POST", prefix, handler));
    }

    public HttpResponse route(HttpRequest request) {
        boolean prefixMatchedForOtherMethod = false;

        for (Route route : routes) {
            if (route.matches(request)) {
                return route.handler().handle(request);
            }
            if (route.pathMatches(request)
                    && !route.method().equalsIgnoreCase(request.method())) {
                prefixMatchedForOtherMethod = true;
            }
        }

        if (prefixMatchedForOtherMethod) {
            return HttpResponse.text(
                    HttpStatus.METHOD_NOT_ALLOWED,
                    "Method Not Allowed\n"
            );
        }

        return HttpResponse.text(
                HttpStatus.NOT_FOUND,
                "Not Found\n"
        );
    }
}