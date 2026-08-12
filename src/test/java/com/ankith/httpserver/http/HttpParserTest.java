package com.ankith.httpserver.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HttpParserTest {

    private final HttpParser parser = new HttpParser();

    @Test
    void shouldParseValidGetRequest() throws Exception {

        String request =
                "GET /hello HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";

        HttpRequest parsed = parser.parse(
                new ByteArrayInputStream(
                        request.getBytes(StandardCharsets.US_ASCII)
                )
        );

        assertEquals("GET", parsed.method());
        assertEquals("/hello", parsed.target());
        assertEquals("HTTP/1.1", parsed.version());
        assertEquals("localhost", parsed.header("host"));
        assertEquals(0, parsed.body().length);
    }

    @Test
    void shouldParsePostRequestWithBody() throws Exception {

        String request =
                "POST /users HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: 5\r\n" +
                        "\r\n" +
                        "hello";

        HttpRequest parsed = parser.parse(
                new ByteArrayInputStream(
                        request.getBytes(StandardCharsets.US_ASCII)
                )
        );

        assertEquals("POST", parsed.method());
        assertEquals("/users", parsed.target());
        assertEquals("hello",
                new String(parsed.body(), StandardCharsets.US_ASCII));
    }

    @Test
    void shouldThrowForMissingRequestLine() {

        String request = "";

        assertThrows(
                HttpParser.BadRequestException.class,
                () -> parser.parse(
                        new ByteArrayInputStream(
                                request.getBytes(StandardCharsets.US_ASCII)
                        )
                )
        );
    }

    @Test
    void shouldThrowForMalformedRequestLine() {

        String request =
                "GET /hello\r\n" +
                        "\r\n";

        assertThrows(
                HttpParser.BadRequestException.class,
                () -> parser.parse(
                        new ByteArrayInputStream(
                                request.getBytes(StandardCharsets.US_ASCII)
                        )
                )
        );
    }

    @Test
    void shouldThrowForUnsupportedHttpVersion() {

        String request =
                "GET /hello HTTP/1.0\r\n" +
                        "\r\n";

        assertThrows(
                HttpParser.BadRequestException.class,
                () -> parser.parse(
                        new ByteArrayInputStream(
                                request.getBytes(StandardCharsets.US_ASCII)
                        )
                )
        );
    }

    @Test
    void shouldThrowForMalformedHeader() {

        String request =
                "GET /hello HTTP/1.1\r\n" +
                        "Host localhost\r\n" +
                        "\r\n";

        assertThrows(
                HttpParser.BadRequestException.class,
                () -> parser.parse(
                        new ByteArrayInputStream(
                                request.getBytes(StandardCharsets.US_ASCII)
                        )
                )
        );
    }

    @Test
    void shouldThrowForInvalidContentLength() {

        String request =
                "POST /hello HTTP/1.1\r\n" +
                        "Content-Length: abc\r\n" +
                        "\r\n";

        assertThrows(
                HttpParser.BadRequestException.class,
                () -> parser.parse(
                        new ByteArrayInputStream(
                                request.getBytes(StandardCharsets.US_ASCII)
                        )
                )
        );
    }

    @Test
    void shouldThrowForNegativeContentLength() {

        String request =
                "POST /hello HTTP/1.1\r\n" +
                        "Content-Length: -1\r\n" +
                        "\r\n";

        assertThrows(
                HttpParser.BadRequestException.class,
                () -> parser.parse(
                        new ByteArrayInputStream(
                                request.getBytes(StandardCharsets.US_ASCII)
                        )
                )
        );
    }

    @Test
    void shouldThrowForIncompleteBody() {

        String request =
                "POST /hello HTTP/1.1\r\n" +
                        "Content-Length: 10\r\n" +
                        "\r\n" +
                        "abc";

        assertThrows(
                HttpParser.BadRequestException.class,
                () -> parser.parse(
                        new ByteArrayInputStream(
                                request.getBytes(StandardCharsets.US_ASCII)
                        )
                )
        );
    }

    @Test
    void shouldNormalizeHeaderNamesToLowerCase() throws Exception {

        String request =
                "GET /hello HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n";

        HttpRequest parsed = parser.parse(
                new ByteArrayInputStream(
                        request.getBytes(StandardCharsets.US_ASCII)
                )
        );

        assertEquals("localhost", parsed.header("host"));
        assertEquals("localhost", parsed.header("HOST"));
        assertEquals("localhost", parsed.header("Host"));
    }
}