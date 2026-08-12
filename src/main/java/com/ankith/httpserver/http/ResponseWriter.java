package com.ankith.httpserver.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class ResponseWriter {

    public void write(OutputStream output, HttpResponse response)
            throws IOException {

        StringBuilder head = new StringBuilder();

        head.append("HTTP/1.1 ")
                .append(response.status().code())
                .append(" ")
                .append(response.status().reason())
                .append("\r\n");

        for (Map.Entry<String, String> header :
                response.headers().entrySet()) {

            head.append(header.getKey())
                    .append(": ")
                    .append(header.getValue())
                    .append("\r\n");
        }

        head.append("\r\n");

        output.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        output.write(response.body());
        output.flush();
    }
}