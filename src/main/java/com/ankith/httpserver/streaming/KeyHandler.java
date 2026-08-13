package com.ankith.httpserver.streaming;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;
import com.ankith.httpserver.http.HttpStatus;
import com.ankith.httpserver.router.Handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves the AES-128 HLS key at GET /hls/encrypted/key.
 *
 * Requires the X-Stream-Token header. The token is a project-level
 * shared secret for local development only - this is NOT production
 * key management or DRM (design doc sections 24 and 46.3).
 */
public final class KeyHandler implements Handler {

    private static final int AES_128_KEY_LENGTH = 16;

    private final Path keyFile;
    private final String expectedToken;

    public KeyHandler(Path keyFile, String expectedToken) {
        this.keyFile = keyFile;
        this.expectedToken = expectedToken;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {

        if (!"GET".equalsIgnoreCase(request.method())) {
            return HttpResponse.empty(HttpStatus.METHOD_NOT_ALLOWED);
        }

        String token = request.header("x-stream-token");

        if (token == null || !constantTimeEquals(expectedToken, token)) {
            return HttpResponse.empty(HttpStatus.UNAUTHORIZED);
        }

        try {
            byte[] key = Files.readAllBytes(keyFile);

            if (key.length != AES_128_KEY_LENGTH) {
                return HttpResponse.text(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Invalid AES key\n"
                );
            }

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/octet-stream");
            headers.put("Cache-Control", "no-store");

            return new HttpResponse(HttpStatus.OK, headers, key);

        } catch (IOException e) {
            return HttpResponse.text(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to read key\n"
            );
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);

        if (a.length != b.length) {
            return false;
        }

        int result = 0;

        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }

        return result == 0;
    }
}
