package com.ankith.httpserver.router;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;

public interface Handler {
    HttpResponse handle(HttpRequest request);
}