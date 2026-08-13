package com.ankith.httpserver.handler;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;
import com.ankith.httpserver.http.HttpStatus;
import com.ankith.httpserver.router.Handler;

public final class RootHandler implements Handler {

    @Override
    public HttpResponse handle(HttpRequest request) {
        return HttpResponse.text(
                HttpStatus.OK,
                "Multithreaded HTTP/1.1 + HLS origin server is running.\n"
        );
    }
}
