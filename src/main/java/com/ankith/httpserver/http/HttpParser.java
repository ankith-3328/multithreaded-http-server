package com.ankith.httpserver.http;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HttpParser {

    private static final int MAX_HEADER_LINE = 8 * 1024;
    private static final int MAX_HEADERS = 100;
    private static final int MAX_BODY = 10 * 1024 * 1024;

    public HttpRequest parse(InputStream input) throws IOException {
        String requestLine = readLine(input);

        if (requestLine == null || requestLine.isBlank()) {
            throw new BadRequestException("Missing request line");
        }

        String[] parts = requestLine.split(" ");
        if (parts.length != 3) {
            throw new BadRequestException("Malformed request line");
        }

        String method = parts[0];
        String target = parts[1];
        String version = parts[2];

        if (!version.equals("HTTP/1.1")) {
            throw new BadRequestException("Only HTTP/1.1 supported");
        }

        Map<String, String> headers = new LinkedHashMap<>();

        for (int i = 0; i < MAX_HEADERS; i++) {
            String line = readLine(input);

            if (line == null) {
                throw new BadRequestException("Unexpected EOF");
            }

            if (line.isEmpty()) {
                break;
            }

            int colon = line.indexOf(':');

            if (colon <= 0) {
                throw new BadRequestException("Malformed header");
            }

            String name = line.substring(0, colon)
                    .trim()
                    .toLowerCase();

            String value = line.substring(colon + 1).trim();

            headers.put(name, value);
        }

        int contentLength = parseContentLength(headers);

        byte[] body = input.readNBytes(contentLength);

        if (body.length != contentLength) {
            throw new BadRequestException("Incomplete body");
        }

        return new HttpRequest(
                method,
                target,
                version,
                headers,
                body
        );
    }

    private int parseContentLength(Map<String, String> headers) {
        String value = headers.get("content-length");

        if (value == null) {
            return 0;
        }

        try {
            int length = Integer.parseInt(value);

            if (length < 0 || length > MAX_BODY) {
                throw new BadRequestException("Invalid body size");
            }

            return length;
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid Content-Length");
        }
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int previous = -1;

        while (true) {
            int current = input.read();

            if (current == -1) {
                if (buffer.size() == 0) {
                    return null;
                }
                throw new EOFException("Unexpected EOF");
            }

            if (previous == '\r' && current == '\n') {
                byte[] bytes = buffer.toByteArray();
                return new String(
                        bytes,
                        0,
                        bytes.length - 1,
                        StandardCharsets.US_ASCII
                );
            }

            buffer.write(current);

            if (buffer.size() > MAX_HEADER_LINE) {
                throw new BadRequestException("Header line too long");
            }

            previous = current;
        }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }
}