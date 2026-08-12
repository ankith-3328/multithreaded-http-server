package com.ankith.httpserver.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ResponseWriterTest {

    private final ResponseWriter writer = new ResponseWriter();

    @Test
    void shouldWriteValidHttpResponse() throws Exception {

        HttpResponse response =
                HttpResponse.text(HttpStatus.OK, "hello");

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        writer.write(output, response);

        String result =
                output.toString(StandardCharsets.US_ASCII);

        assertTrue(result.startsWith("HTTP/1.1 200 OK"));
        assertTrue(result.contains("Content-Length: 5"));
        assertTrue(result.contains(
                "Content-Type: text/plain; charset=utf-8"
        ));
        assertTrue(result.endsWith("hello"));
    }
}