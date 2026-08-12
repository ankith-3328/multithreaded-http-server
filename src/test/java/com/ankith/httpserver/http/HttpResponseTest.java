package com.ankith.httpserver.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpResponseTest {

    @Test
    void shouldAutomaticallySetContentLength() {

        HttpResponse response =
                HttpResponse.text(HttpStatus.OK, "hello");

        assertEquals(
                "5",
                response.headers().get("Content-Length")
        );
    }

    @Test
    void shouldCreateEmptyResponse() {

        HttpResponse response =
                HttpResponse.empty(HttpStatus.OK);

        assertEquals(0, response.body().length);

        assertEquals(
                "0",
                response.headers().get("Content-Length")
        );
    }
}