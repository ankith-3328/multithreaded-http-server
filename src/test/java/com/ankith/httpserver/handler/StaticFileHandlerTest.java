package com.ankith.httpserver.handler;

import com.ankith.httpserver.handler.StaticFileHandler;
import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;
import com.ankith.httpserver.http.HttpStatus;
import com.ankith.httpserver.streaming.FileRangeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StaticFileHandlerTest {

    @TempDir
    Path tempDir;

    private StaticFileHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("hello.txt"), "hello world");
        Files.createDirectories(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("sub").resolve("nested.txt"), "nested");

        handler = new StaticFileHandler(tempDir, new FileRangeService());
    }

    private HttpRequest get(String target) {
        return new HttpRequest("GET", target, "HTTP/1.1", Map.of(), new byte[0]);
    }

    @Test
    void servesExistingFile() {
        HttpResponse response = handler.handle(get("/hello.txt"));

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("hello world", new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void returns404ForMissingFile() {
        HttpResponse response = handler.handle(get("/missing.txt"));

        assertEquals(HttpStatus.NOT_FOUND, response.status());
    }

    @Test
    void blocksPathTraversal() {
        HttpResponse response = handler.handle(get("/../../../../etc/passwd"));

        assertEquals(HttpStatus.BAD_REQUEST, response.status());
    }

    @Test
    void servesNestedFile() {
        HttpResponse response = handler.handle(get("/sub/nested.txt"));

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("nested", new String(response.body(), StandardCharsets.UTF_8));
    }
}
