# Design Document --- Multithreaded HTTP/1.1 Server + HLS Streaming Origin

## 1. Goal

Build a multithreaded HTTP/1.1 server from raw TCP sockets in Java 21,
with no HTTP/server framework, and extend it into a small streaming
origin supporting:

-   HTTP/1.1 request parsing and response serialization
-   GET/POST
-   routing
-   static file serving
-   path traversal protection
-   HTTP Range requests (`200`, `206`, `416`)
-   persistent HTTP/1.1 keep-alive
-   fixed `ExecutorService` worker pool
-   HLS VOD:
    -   clear HLS (`.m3u8` + `.ts`)
    -   AES-128 encrypted HLS
    -   authenticated key endpoint
-   MPEG-DASH VOD (`.mpd` + `.m4s`)
-   live metrics endpoint
-   unit/integration tests
-   reproducible streaming benchmark

Media is **not transcoded or packaged by the Java server**. FFmpeg
creates the fixtures offline. The server only delivers the generated
files correctly.

------------------------------------------------------------------------

# 2. What We Are Building

``` text
Browser / hls.js / dash.js
            |
            | HTTP/1.1
            v
+---------------------------+
|       ServerSocket        |
+-------------+-------------+
              |
              v
+---------------------------+
|   Fixed ExecutorService   |
+-------------+-------------+
              |
              v
+---------------------------+
|      ClientHandler        |
|  keep-alive request loop  |
+-------------+-------------+
              |
              v
+---------------------------+
|       HttpParser          |
+-------------+-------------+
              |
              v
+---------------------------+
|         Router            |
+------+------+------+------+
       |      |      |
       v      v      v
   API/File  HLS    DASH
              |
       +------+------+
       |      |      |
       v      v      v
   playlist segment key
       |      |
       |    Range
       |      |
       +------+------+
              |
              v
         File System
```

## Core principle

Keep responsibilities separate:

``` text
ClientHandler  -> TCP connection lifecycle
HttpParser     -> HTTP syntax
Router         -> route selection
Handler        -> application semantics
RangeService   -> byte-range serving
HttpResponse   -> HTTP response representation
ResponseWriter -> bytes on socket
```

`HlsHandler` should not know about sockets.

------------------------------------------------------------------------

# 3. Two-Developer Ownership

We split work by architecture, not frontend/backend.

## Developer A --- HTTP Core + Concurrency

Owns:

``` text
server/
http/
router/
config/
logging/
metrics/
static file infrastructure
```

Primary responsibilities:

1.  Maven/Java 21 skeleton
2.  `ServerSocket`
3.  `ExecutorService`
4.  `ClientHandler`
5.  HTTP parser
6.  HTTP response writer
7.  Router
8.  HTTP status codes
9.  keep-alive
10. graceful shutdown
11. metrics
12. base API handlers
13. benchmark infrastructure

## Developer B --- Streaming + Media

Owns:

``` text
streaming/
media/
player/
range/
streaming tests
prepare-media.sh
streaming_benchmark.py
```

Primary responsibilities:

1.  FFmpeg media fixtures
2.  HLS clear delivery
3.  HLS encrypted delivery
4.  key endpoint
5.  DASH delivery
6.  Range parser/service
7.  MIME types
8.  browser player
9.  streaming integration tests
10. streaming benchmark

## Shared contracts

Define these interfaces/classes in the first hour:

``` java
public interface Handler {
    HttpResponse handle(HttpRequest request);
}
```

``` java
public record HttpRequest(
        String method,
        String target,
        String version,
        Map<String, String> headers,
        byte[] body
) {}
```

``` java
public record HttpResponse(
        HttpStatus status,
        Map<String, String> headers,
        byte[] body
) {}
```

Developer A can then build the server around these contracts while
Developer B writes handlers against them.

------------------------------------------------------------------------

# 4. Repository Structure

``` text
multithreaded-http-server/
├── pom.xml
├── README.md
├── DESIGN.md
├── .gitignore
│
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/ankith/httpserver/
│   │   │       ├── Main.java
│   │   │       │
│   │   │       ├── server/
│   │   │       │   ├── HttpServer.java
│   │   │       │   ├── ClientHandler.java
│   │   │       │   └── KeepAliveManager.java
│   │   │       │
│   │   │       ├── http/
│   │   │       │   ├── HttpRequest.java
│   │   │       │   ├── HttpResponse.java
│   │   │       │   ├── HttpParser.java
│   │   │       │   ├── HttpStatus.java
│   │   │       │   ├── ResponseWriter.java
│   │   │       │   ├── Range.java
│   │   │       │   └── RangeParser.java
│   │   │       │
│   │   │       ├── router/
│   │   │       │   ├── Handler.java
│   │   │       │   ├── Route.java
│   │   │       │   └── Router.java
│   │   │       │
│   │   │       ├── handler/
│   │   │       │   ├── RootHandler.java
│   │   │       │   ├── HelloHandler.java
│   │   │       │   ├── EchoHandler.java
│   │   │       │   └── StaticFileHandler.java
│   │   │       │
│   │   │       ├── streaming/
│   │   │       │   ├── HlsHandler.java
│   │   │       │   ├── DashHandler.java
│   │   │       │   ├── KeyHandler.java
│   │   │       │   ├── MimeTypes.java
│   │   │       │   └── FileRangeService.java
│   │   │       │
│   │   │       ├── metrics/
│   │   │       │   ├── MetricsRegistry.java
│   │   │       │   └── MetricsHandler.java
│   │   │       │
│   │   │       ├── config/
│   │   │       │   └── ServerConfig.java
│   │   │       │
│   │   │       └── logging/
│   │   │           └── RequestLogger.java
│   │   │
│   │   └── resources/
│   │       └── public/
│   │           ├── index.html
│   │           └── player/
│   │               └── player.html
│   │
│   └── test/
│       └── java/com/ankith/httpserver/
│           ├── HttpParserTest.java
│           ├── RouterTest.java
│           ├── RangeParserTest.java
│           ├── StaticFileHandlerTest.java
│           └── StreamingHandlerTest.java
│
├── media/
│   ├── prepare-media.sh
│   ├── source/
│   │   └── sample.mp4
│   ├── hls-clear/
│   │   ├── playlist.m3u8
│   │   ├── seg-000.ts
│   │   └── ...
│   ├── hls-encrypted/
│   │   ├── playlist.m3u8
│   │   ├── stream.key
│   │   ├── seg-000.ts
│   │   └── ...
│   └── dash/
│       ├── manifest.mpd
│       ├── init.m4s
│       └── ...
│
├── benchmark/
│   ├── benchmark.py
│   ├── streaming_benchmark.py
│   └── results/
│
└── docs/
    └── architecture.md
```

Do not commit production secrets. For this project the HLS key is a
local fixture, but document that it is not production key management.

------------------------------------------------------------------------

# 5. HTTP Architecture

## Request lifecycle

``` text
accept()
   |
   v
Socket
   |
   v
ClientHandler
   |
   +--> read request bytes
   |
   +--> HttpParser
   |
   +--> Router
   |
   +--> Handler
   |
   +--> HttpResponse
   |
   +--> ResponseWriter
   |
   +--> keep-alive decision
   |
   +--> next request OR close
```

With keep-alive:

``` java
while (connectionOpen) {
    HttpRequest request = parser.parse(input);

    HttpResponse response = router.route(request);

    writer.write(output, response);

    if (!keepAliveManager.shouldKeepAlive(request, response)) {
        break;
    }
}
```

------------------------------------------------------------------------

# 6. Maven Configuration

Use Java 21.

``` xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="
           http://maven.apache.org/POM/4.0.0
           https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <groupId>com.ankith.httpserver</groupId>
    <artifactId>multithreaded-http-server</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.11.0</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.0</version>
            </plugin>
        </plugins>
    </build>
</project>
```

------------------------------------------------------------------------

# 7. Configuration

``` java
package com.ankith.httpserver.config;

public record ServerConfig(
        int port,
        int workerThreads,
        String staticDirectory,
        String mediaDirectory,
        int keepAliveTimeoutMs,
        boolean metricsEnabled,
        String streamToken
) {
    public static ServerConfig defaults() {
        return new ServerConfig(
                8080,
                8,
                "src/main/resources/public",
                "media",
                10_000,
                true,
                "dev-secret"
        );
    }
}
```

Do not hard-code configuration throughout handlers.

------------------------------------------------------------------------

# 8. HTTP Status

``` java
package com.ankith.httpserver.http;

public enum HttpStatus {
    OK(200, "OK"),
    PARTIAL_CONTENT(206, "Partial Content"),
    BAD_REQUEST(400, "Bad Request"),
    UNAUTHORIZED(401, "Unauthorized"),
    NOT_FOUND(404, "Not Found"),
    METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
    RANGE_NOT_SATISFIABLE(416, "Range Not Satisfiable"),
    INTERNAL_SERVER_ERROR(500, "Internal Server Error");

    private final int code;
    private final String reason;

    HttpStatus(int code, String reason) {
        this.code = code;
        this.reason = reason;
    }

    public int code() {
        return code;
    }

    public String reason() {
        return reason;
    }
}
```

------------------------------------------------------------------------

# 9. HttpRequest

Normalize header names to lowercase when parsing.

``` java
package com.ankith.httpserver.http;

import java.util.Map;

public record HttpRequest(
        String method,
        String target,
        String version,
        Map<String, String> headers,
        byte[] body
) {
    public String header(String name) {
        return headers.get(name.toLowerCase());
    }
}
```

------------------------------------------------------------------------

# 10. HttpResponse

``` java
package com.ankith.httpserver.http;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public record HttpResponse(
        HttpStatus status,
        Map<String, String> headers,
        byte[] body
) {
    public HttpResponse {
        headers = new LinkedHashMap<>(headers);
        body = body == null ? new byte[0] : body;

        headers.putIfAbsent(
                "Content-Length",
                String.valueOf(body.length)
        );
    }

    public static HttpResponse text(HttpStatus status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/plain; charset=utf-8");

        return new HttpResponse(status, headers, bytes);
    }

    public static HttpResponse empty(HttpStatus status) {
        return new HttpResponse(status, Map.of(), new byte[0]);
    }
}
```

------------------------------------------------------------------------

# 11. HTTP Parser

Responsibilities:

-   read request line
-   parse method
-   parse target
-   parse HTTP version
-   parse headers
-   read body using `Content-Length`
-   reject malformed requests
-   prevent unbounded header/body allocation

Basic implementation:

``` java
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
                if (buffer.isEmpty()) {
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
```

For production-grade HTTP parsing, more edge cases would be required.
This project deliberately implements the subset needed by the benchmark
and streaming workload.

------------------------------------------------------------------------

# 12. Response Writer

``` java
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
```

------------------------------------------------------------------------

# 13. Handler Interface

``` java
package com.ankith.httpserver.router;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;

public interface Handler {
    HttpResponse handle(HttpRequest request);
}
```

Handlers return data. They do not write to sockets.

------------------------------------------------------------------------

# 14. Router

Keep routing simple.

``` java
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
        for (Route route : routes) {
            if (route.matches(request)) {
                return route.handler().handle(request);
            }
        }

        return HttpResponse.text(
                HttpStatus.NOT_FOUND,
                "Not Found\n"
        );
    }
}
```

``` java
package com.ankith.httpserver.router;

import com.ankith.httpserver.http.HttpRequest;

public record Route(
        String method,
        String prefix,
        Handler handler
) {
    public boolean matches(HttpRequest request) {
        return method.equalsIgnoreCase(request.method())
                && request.target().startsWith(prefix);
    }
}
```

For the final implementation, add explicit path/template matching rather
than relying on broad prefixes when routes overlap.

------------------------------------------------------------------------

# 15. HTTP Server

``` java
package com.ankith.httpserver.server;

import com.ankith.httpserver.config.ServerConfig;
import com.ankith.httpserver.router.Router;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HttpServer {

    private final ServerConfig config;
    private final Router router;
    private final ExecutorService pool;

    private volatile boolean running;
    private ServerSocket serverSocket;

    public HttpServer(ServerConfig config, Router router) {
        this.config = config;
        this.router = router;
        this.pool = Executors.newFixedThreadPool(
                config.workerThreads()
        );
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(config.port());
        running = true;

        System.out.println(
                "HTTP server listening on port "
                        + config.port()
        );

        while (running) {
            try {
                Socket client = serverSocket.accept();

                pool.submit(
                        new ClientHandler(
                                client,
                                router,
                                config
                        )
                );

            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void stop() {
        running = false;

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }

        pool.shutdown();
    }
}
```

------------------------------------------------------------------------

# 16. ClientHandler

This is one of the most important classes.

``` java
package com.ankith.httpserver.server;

import com.ankith.httpserver.config.ServerConfig;
import com.ankith.httpserver.http.*;
import com.ankith.httpserver.router.Router;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;

public final class ClientHandler implements Runnable {

    private final Socket socket;
    private final Router router;
    private final ServerConfig config;

    public ClientHandler(
            Socket socket,
            Router router,
            ServerConfig config
    ) {
        this.socket = socket;
        this.router = router;
        this.config = config;
    }

    @Override
    public void run() {
        String thread = Thread.currentThread().getName();

        try (socket) {

            socket.setSoTimeout(
                    config.keepAliveTimeoutMs()
            );

            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            HttpParser parser = new HttpParser();
            ResponseWriter writer = new ResponseWriter();

            while (true) {
                HttpRequest request;

                try {
                    request = parser.parse(input);
                } catch (SocketTimeoutException timeout) {
                    break;
                } catch (HttpParser.BadRequestException bad) {
                    writer.write(
                            output,
                            HttpResponse.text(
                                    HttpStatus.BAD_REQUEST,
                                    bad.getMessage()
                            )
                    );
                    break;
                }

                HttpResponse response;

                try {
                    response = router.route(request);
                } catch (Exception e) {
                    e.printStackTrace();

                    response = HttpResponse.text(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Internal Server Error\n"
                    );
                }

                boolean keepAlive =
                        KeepAliveManager.shouldKeepAlive(request);

                response = KeepAliveManager.withConnectionHeader(
                        response,
                        keepAlive
                );

                writer.write(output, response);

                if (!keepAlive) {
                    break;
                }
            }

        } catch (IOException e) {
            System.err.println(
                    thread + " connection error: "
                            + e.getMessage()
            );
        }
    }
}
```

------------------------------------------------------------------------

# 17. Keep-Alive

For HTTP/1.1:

``` text
default = keep-alive
```

unless the client explicitly says:

``` http
Connection: close
```

Implementation:

``` java
package com.ankith.httpserver.server;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;

import java.util.LinkedHashMap;
import java.util.Map;

public final class KeepAliveManager {

    private KeepAliveManager() {}

    public static boolean shouldKeepAlive(HttpRequest request) {
        String connection = request.header("connection");

        return connection == null
                || !connection.equalsIgnoreCase("close");
    }

    public static HttpResponse withConnectionHeader(
            HttpResponse response,
            boolean keepAlive
    ) {
        Map<String, String> headers =
                new LinkedHashMap<>(response.headers());

        headers.put(
                "Connection",
                keepAlive ? "keep-alive" : "close"
        );

        return new HttpResponse(
                response.status(),
                headers,
                response.body()
        );
    }
}
```

Always provide `Content-Length` so the client can determine response
boundaries without closing the connection.

------------------------------------------------------------------------

# 18. Static File Security

Never directly concatenate:

``` java
mediaRoot + requestPath
```

because this can permit:

``` text
../../../../etc/passwd
```

Use canonical/normalized paths.

``` java
Path root = Paths.get(rootDirectory)
        .toAbsolutePath()
        .normalize();

Path requested = root
        .resolve(relativePath)
        .normalize();

if (!requested.startsWith(root)) {
    return HttpResponse.text(
            HttpStatus.BAD_REQUEST,
            "Invalid path\n"
    );
}
```

The same principle must be used for HLS stream directories.

------------------------------------------------------------------------

# 19. Range Model

``` java
package com.ankith.httpserver.http;

public record Range(long start, long end) {

    public long length() {
        return end - start + 1;
    }
}
```

------------------------------------------------------------------------

# 20. RangeParser

Supported:

``` text
bytes=1000-1999
bytes=1000-
bytes=-500
```

Multi-range is intentionally out of scope.

``` java
package com.ankith.httpserver.http;

public final class RangeParser {

    private RangeParser() {}

    public static Range parse(
            String header,
            long fileSize
    ) {
        if (header == null
                || !header.startsWith("bytes=")) {
            throw new IllegalArgumentException("Invalid Range");
        }

        String value = header.substring(6);

        if (value.contains(",")) {
            throw new IllegalArgumentException(
                    "Multi-range unsupported"
            );
        }

        String[] parts = value.split("-", -1);

        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid Range");
        }

        long start;
        long end;

        try {
            if (parts[0].isEmpty()) {
                long suffixLength = Long.parseLong(parts[1]);

                if (suffixLength <= 0) {
                    throw new IllegalArgumentException();
                }

                start = Math.max(
                        0,
                        fileSize - suffixLength
                );

                end = fileSize - 1;

            } else {
                start = Long.parseLong(parts[0]);

                if (parts[1].isEmpty()) {
                    end = fileSize - 1;
                } else {
                    end = Long.parseLong(parts[1]);
                }
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid Range");
        }

        if (start < 0
                || start >= fileSize
                || end < start) {
            throw new IllegalArgumentException(
                    "Range Not Satisfiable"
            );
        }

        end = Math.min(end, fileSize - 1);

        return new Range(start, end);
    }
}
```

------------------------------------------------------------------------

# 21. Shared FileRangeService

Do not duplicate this logic in HLS, DASH, and static handlers.

``` java
package com.ankith.httpserver.streaming;

import com.ankith.httpserver.http.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FileRangeService {

    public HttpResponse serve(
            Path file,
            String contentType,
            String rangeHeader
    ) {
        try {
            if (!Files.exists(file)
                    || !Files.isRegularFile(file)) {
                return HttpResponse.text(
                        HttpStatus.NOT_FOUND,
                        "File not found\n"
                );
            }

            long size = Files.size(file);

            if (rangeHeader == null) {
                byte[] body = Files.readAllBytes(file);

                Map<String, String> headers =
                        new LinkedHashMap<>();

                headers.put(
                        "Content-Type",
                        contentType
                );
                headers.put(
                        "Accept-Ranges",
                        "bytes"
                );

                return new HttpResponse(
                        HttpStatus.OK,
                        headers,
                        body
                );
            }

            Range range;

            try {
                range = RangeParser.parse(
                        rangeHeader,
                        size
                );
            } catch (IllegalArgumentException e) {
                Map<String, String> headers =
                        new LinkedHashMap<>();

                headers.put(
                        "Content-Range",
                        "bytes */" + size
                );

                return new HttpResponse(
                        HttpStatus.RANGE_NOT_SATISFIABLE,
                        headers,
                        new byte[0]
                );
            }

            byte[] full = Files.readAllBytes(file);

            int length = Math.toIntExact(
                    range.length()
            );

            byte[] body = new byte[length];

            System.arraycopy(
                    full,
                    Math.toIntExact(range.start()),
                    body,
                    0,
                    length
            );

            Map<String, String> headers =
                    new LinkedHashMap<>();

            headers.put(
                    "Content-Type",
                    contentType
            );
            headers.put(
                    "Accept-Ranges",
                    "bytes"
            );
            headers.put(
                    "Content-Range",
                    "bytes "
                            + range.start()
                            + "-"
                            + range.end()
                            + "/"
                            + size
            );

            return new HttpResponse(
                    HttpStatus.PARTIAL_CONTENT,
                    headers,
                    body
            );

        } catch (IOException e) {
            return HttpResponse.text(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to read file\n"
            );
        }
    }
}
```

### Important improvement

For large media files, do **not** use `Files.readAllBytes()` in the
final version. It is included above to make the first implementation
understandable.

The production-oriented version should stream the selected file region
directly to the socket, or introduce a response-body abstraction such
as:

``` java
interface ResponseBody {
    long contentLength();
    void writeTo(OutputStream output) throws IOException;
}
```

That avoids loading a 100 MB segment into heap memory.

Implement this after the basic version works.

------------------------------------------------------------------------

# 22. MIME Types

``` java
package com.ankith.httpserver.streaming;

public final class MimeTypes {

    private MimeTypes() {}

    public static String forPath(String path) {
        if (path.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }

        if (path.endsWith(".ts")) {
            return "video/mp2t";
        }

        if (path.endsWith(".mpd")) {
            return "application/dash+xml";
        }

        if (path.endsWith(".m4s")) {
            return "video/mp4";
        }

        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }

        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }

        if (path.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        }

        return "application/octet-stream";
    }
}
```

------------------------------------------------------------------------

# 23. HlsHandler

Routes:

``` text
GET /hls/clear/playlist.m3u8
GET /hls/clear/seg-000.ts

GET /hls/encrypted/playlist.m3u8
GET /hls/encrypted/seg-000.ts
```

Implementation:

``` java
package com.ankith.httpserver.streaming;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;
import com.ankith.httpserver.http.HttpStatus;
import com.ankith.httpserver.router.Handler;

import java.nio.file.Path;
import java.util.Set;

public final class HlsHandler implements Handler {

    private static final Set<String> STREAMS =
            Set.of("clear", "encrypted");

    private final Path mediaRoot;
    private final FileRangeService rangeService;

    public HlsHandler(
            Path mediaRoot,
            FileRangeService rangeService
    ) {
        this.mediaRoot = mediaRoot;
        this.rangeService = rangeService;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        String path = request.target();

        String prefix = "/hls/";

        if (!path.startsWith(prefix)) {
            return HttpResponse.text(
                    HttpStatus.NOT_FOUND,
                    "Not Found\n"
            );
        }

        String relative = path.substring(prefix.length());
        String[] parts = relative.split("/");

        if (parts.length != 2) {
            return HttpResponse.text(
                    HttpStatus.NOT_FOUND,
                    "Not Found\n"
            );
        }

        String stream = parts[0];
        String fileName = parts[1];

        if (!STREAMS.contains(stream)) {
            return HttpResponse.text(
                    HttpStatus.NOT_FOUND,
                    "Not Found\n"
            );
        }

        if (!isSafeFileName(fileName)) {
            return HttpResponse.text(
                    HttpStatus.BAD_REQUEST,
                    "Invalid path\n"
            );
        }

        Path root = mediaRoot
                .resolve(
                        stream.equals("clear")
                                ? "hls-clear"
                                : "hls-encrypted"
                )
                .normalize();

        Path file = root
                .resolve(fileName)
                .normalize();

        if (!file.startsWith(root)) {
            return HttpResponse.text(
                    HttpStatus.BAD_REQUEST,
                    "Invalid path\n"
            );
        }

        String contentType =
                MimeTypes.forPath(fileName);

        return rangeService.serve(
                file,
                contentType,
                request.header("range")
        );
    }

    private boolean isSafeFileName(String name) {
        return !name.contains("..")
                && !name.contains("/")
                && !name.contains("\\");
    }
}
```

------------------------------------------------------------------------

# 24. KeyHandler

The key endpoint is:

``` text
GET /hls/encrypted/key
```

The token is a project-level shared secret, not real DRM.

``` java
package com.ankith.httpserver.streaming;

import com.ankith.httpserver.http.*;
import com.ankith.httpserver.router.Handler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class KeyHandler implements Handler {

    private final Path keyFile;
    private final String expectedToken;

    public KeyHandler(
            Path keyFile,
            String expectedToken
    ) {
        this.keyFile = keyFile;
        this.expectedToken = expectedToken;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {

        if (!"GET".equalsIgnoreCase(request.method())) {
            return HttpResponse.empty(
                    HttpStatus.METHOD_NOT_ALLOWED
            );
        }

        String token =
                request.header("x-stream-token");

        if (token == null
                || !constantTimeEquals(
                        expectedToken,
                        token
                )) {

            return HttpResponse.empty(
                    HttpStatus.UNAUTHORIZED
            );
        }

        try {
            byte[] key = Files.readAllBytes(keyFile);

            if (key.length != 16) {
                return HttpResponse.text(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Invalid AES key\n"
                );
            }

            return new HttpResponse(
                    HttpStatus.OK,
                    Map.of(
                            "Content-Type",
                            "application/octet-stream",
                            "Cache-Control",
                            "no-store"
                    ),
                    key
            );

        } catch (IOException e) {
            return HttpResponse.text(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to read key\n"
            );
        }
    }

    private boolean constantTimeEquals(
            String expected,
            String actual
    ) {
        byte[] a = expected.getBytes();
        byte[] b = actual.getBytes();

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
```

------------------------------------------------------------------------

# 25. DASH Handler

Routes:

``` text
GET /dash/manifest.mpd
GET /dash/init.m4s
GET /dash/seg-000.m4s
```

``` java
package com.ankith.httpserver.streaming;

import com.ankith.httpserver.http.*;
import com.ankith.httpserver.router.Handler;

import java.nio.file.Path;

public final class DashHandler implements Handler {

    private final Path dashRoot;
    private final FileRangeService rangeService;

    public DashHandler(
            Path dashRoot,
            FileRangeService rangeService
    ) {
        this.dashRoot = dashRoot;
        this.rangeService = rangeService;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {

        String prefix = "/dash/";

        if (!request.target().startsWith(prefix)) {
            return HttpResponse.text(
                    HttpStatus.NOT_FOUND,
                    "Not Found\n"
            );
        }

        String fileName =
                request.target().substring(prefix.length());

        if (!isSafe(fileName)) {
            return HttpResponse.text(
                    HttpStatus.BAD_REQUEST,
                    "Invalid path\n"
            );
        }

        Path file = dashRoot
                .resolve(fileName)
                .normalize();

        if (!file.startsWith(dashRoot)) {
            return HttpResponse.text(
                    HttpStatus.BAD_REQUEST,
                    "Invalid path\n"
            );
        }

        return rangeService.serve(
                file,
                MimeTypes.forPath(fileName),
                request.header("range")
        );
    }

    private boolean isSafe(String fileName) {
        return !fileName.contains("..")
                && !fileName.contains("/")
                && !fileName.contains("\\");
    }
}
```

------------------------------------------------------------------------

# 26. StaticFileHandler

Use the same `FileRangeService`.

``` java
package com.ankith.httpserver.handler;

import com.ankith.httpserver.http.*;
import com.ankith.httpserver.router.Handler;
import com.ankith.httpserver.streaming.FileRangeService;
import com.ankith.httpserver.streaming.MimeTypes;

import java.nio.file.Path;

public final class StaticFileHandler
        implements Handler {

    private final Path root;
    private final FileRangeService rangeService;

    public StaticFileHandler(
            Path root,
            FileRangeService rangeService
    ) {
        this.root = root.toAbsolutePath().normalize();
        this.rangeService = rangeService;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {

        String relative =
                request.target().substring(1);

        Path file = root
                .resolve(relative)
                .normalize();

        if (!file.startsWith(root)) {
            return HttpResponse.text(
                    HttpStatus.BAD_REQUEST,
                    "Invalid path\n"
            );
        }

        return rangeService.serve(
                file,
                MimeTypes.forPath(file.toString()),
                request.header("range")
        );
    }
}
```

------------------------------------------------------------------------

# 27. Media Preparation

`media/prepare-media.sh` is not server runtime code.

Example:

``` bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE="$ROOT/source/sample.mp4"

rm -rf "$ROOT/hls-clear"
rm -rf "$ROOT/hls-encrypted"
rm -rf "$ROOT/dash"

mkdir -p "$ROOT/hls-clear"
mkdir -p "$ROOT/hls-encrypted"
mkdir -p "$ROOT/dash"

# Clear HLS
ffmpeg -y \
  -i "$SOURCE" \
  -codec copy \
  -start_number 0 \
  -hls_time 4 \
  -hls_list_size 0 \
  -f hls \
  "$ROOT/hls-clear/playlist.m3u8"

# Generate AES-128 key
openssl rand 16 > "$ROOT/hls-encrypted/stream.key"

cat > "$ROOT/enc.keyinfo" <<EOF
$ROOT/hls-encrypted/stream.key
/hls/encrypted/key
EOF

# Encrypted HLS
ffmpeg -y \
  -i "$SOURCE" \
  -codec copy \
  -start_number 0 \
  -hls_time 4 \
  -hls_list_size 0 \
  -hls_key_info_file "$ROOT/enc.keyinfo" \
  -f hls \
  "$ROOT/hls-encrypted/playlist.m3u8"

# DASH
ffmpeg -y \
  -i "$SOURCE" \
  -codec copy \
  -f dash \
  "$ROOT/dash/manifest.mpd"

echo "Media preparation complete."
```

Verify the generated files independently before connecting them to Java.

------------------------------------------------------------------------

# 28. HLS Playlist

A generated encrypted playlist should contain something equivalent to:

``` m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:4
#EXT-X-KEY:METHOD=AES-128,URI="/hls/encrypted/key",IV=0x...
#EXTINF:4.0,
seg-000.ts
#EXTINF:4.0,
seg-001.ts
#EXTINF:4.0,
seg-002.ts
#EXT-X-ENDLIST
```

The Java server does not generate this playlist. FFmpeg generates it.

The server serves it.

------------------------------------------------------------------------

# 29. Main.java

``` java
package com.ankith.httpserver;

import com.ankith.httpserver.config.ServerConfig;
import com.ankith.httpserver.handler.StaticFileHandler;
import com.ankith.httpserver.metrics.*;
import com.ankith.httpserver.router.Router;
import com.ankith.httpserver.server.HttpServer;
import com.ankith.httpserver.streaming.*;

import java.nio.file.Path;

public final class Main {

    public static void main(String[] args)
            throws Exception {

        ServerConfig config =
                ServerConfig.defaults();

        FileRangeService rangeService =
                new FileRangeService();

        MetricsRegistry metrics =
                new MetricsRegistry();

        Router router = new Router();

        // Base API
        router.get(
                "/hello",
                request -> {
                    metrics.recordRequest(200, 5);
                    return com.ankith.httpserver.http.HttpResponse.text(
                            com.ankith.httpserver.http.HttpStatus.OK,
                            "Hello, world!\n"
                    );
                }
        );

        // Static files
        router.get(
                "/player/",
                new StaticFileHandler(
                        Path.of(config.staticDirectory()),
                        rangeService
                )
        );

        // HLS
        router.get(
                "/hls/",
                new HlsHandler(
                        Path.of(config.mediaDirectory()),
                        rangeService
                )
        );

        // Key
        router.get(
                "/hls/encrypted/key",
                new KeyHandler(
                        Path.of(
                                config.mediaDirectory(),
                                "hls-encrypted",
                                "stream.key"
                        ),
                        config.streamToken()
                )
        );

        // DASH
        router.get(
                "/dash/",
                new DashHandler(
                        Path.of(
                                config.mediaDirectory(),
                                "dash"
                        ),
                        rangeService
                )
        );

        // Metrics
        router.get(
                "/metrics",
                new MetricsHandler(metrics)
        );

        HttpServer server =
                new HttpServer(config, router);

        Runtime.getRuntime().addShutdownHook(
                new Thread(server::stop)
        );

        server.start();
    }
}
```

The final version should wire metrics centrally in `ClientHandler`,
rather than manually incrementing them inside individual handlers.

------------------------------------------------------------------------

# 30. Metrics

Thread-safe registry:

``` java
package com.ankith.httpserver.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class MetricsRegistry {

    private final LongAdder requestsTotal =
            new LongAdder();

    private final LongAdder status200 =
            new LongAdder();

    private final LongAdder status206 =
            new LongAdder();

    private final LongAdder status404 =
            new LongAdder();

    private final LongAdder bytesServed =
            new LongAdder();

    private final AtomicLong activeConnections =
            new AtomicLong();

    private final long startTime =
            System.nanoTime();

    public void recordRequest(
            int status,
            long bytes
    ) {
        requestsTotal.increment();
        bytesServed.add(bytes);

        switch (status) {
            case 200 -> status200.increment();
            case 206 -> status206.increment();
            case 404 -> status404.increment();
        }
    }

    public long requestsTotal() {
        return requestsTotal.sum();
    }

    public long status200() {
        return status200.sum();
    }

    public long status206() {
        return status206.sum();
    }

    public long status404() {
        return status404.sum();
    }

    public long bytesServed() {
        return bytesServed.sum();
    }

    public long activeConnections() {
        return activeConnections.get();
    }

    public void connectionOpened() {
        activeConnections.incrementAndGet();
    }

    public void connectionClosed() {
        activeConnections.decrementAndGet();
    }

    public long uptimeSeconds() {
        return (System.nanoTime() - startTime)
                / 1_000_000_000L;
    }
}
```

------------------------------------------------------------------------

# 31. Metrics Endpoint

Return:

``` text
requests_total 15234
requests_by_status_200 14800
requests_by_status_206 300
requests_by_status_404 120
active_connections 6
bytes_served_total 92839203
uptime_seconds 842
```

This is useful during benchmarking.

------------------------------------------------------------------------

# 32. Browser Player

`player.html` should validate all three protocols.

Conceptually:

``` html
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Streaming Validation</title>
</head>
<body>

<h1>Streaming Validation</h1>

<video id="video" controls width="800"></video>

<script src="https://cdn.jsdelivr.net/npm/hls.js"></script>
<script src="https://cdn.dashjs.org/latest/dash.all.min.js"></script>

<script>
const video = document.getElementById("video");

const url = "/hls/clear/playlist.m3u8";

if (Hls.isSupported()) {
    const hls = new Hls();

    hls.loadSource(url);
    hls.attachMedia(video);

    hls.on(Hls.Events.ERROR, (_, data) => {
        console.error("HLS error:", data);
    });
}
</script>

</body>
</html>
```

For encrypted HLS, the player needs the token to reach the key endpoint.
Because hls.js does not automatically invent your custom
`X-Stream-Token` header, configure its request/XHR hook or use a
development-only URL/token mechanism.

Do not assume that merely putting `X-Stream-Token` in the playlist
causes hls.js to send it.

------------------------------------------------------------------------

# 33. Important Encryption Flow

``` text
                    playlist.m3u8
                          |
                          v
                #EXT-X-KEY detected
                          |
                          v
                GET /encrypted/key
                          |
                 X-Stream-Token
                          |
              +-----------+-----------+
              |                       |
           invalid                   valid
              |                       |
             401                      key
                                      |
                                      v
                           encrypted segment
                                      |
                                      v
                                 AES decrypt
                                      |
                                      v
                                   playback
```

This should be explicitly tested.

------------------------------------------------------------------------

# 34. Testing Plan

## HttpParserTest

Test:

``` text
GET request
POST request
headers
Content-Length
body
malformed request line
malformed headers
invalid Content-Length
oversized body
```

Example:

``` java
@Test
void parsesGetRequest() throws Exception {

    String raw =
            "GET /hello HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "\r\n";

    HttpParser parser = new HttpParser();

    HttpRequest request =
            parser.parse(
                    new ByteArrayInputStream(
                            raw.getBytes()
                    )
            );

    assertEquals("GET", request.method());
    assertEquals("/hello", request.target());
    assertEquals("HTTP/1.1", request.version());
}
```

------------------------------------------------------------------------

# 35. Range Tests

Minimum tests:

``` text
bytes=100-199
bytes=100-
bytes=-100
start >= fileSize
end < start
malformed range
multi-range
```

Example:

``` java
@Test
void parsesNormalRange() {

    Range range =
            RangeParser.parse(
                    "bytes=100-199",
                    1000
            );

    assertEquals(100, range.start());
    assertEquals(199, range.end());
    assertEquals(100, range.length());
}
```

------------------------------------------------------------------------

# 36. HLS Integration Tests

Test:

``` text
GET /hls/clear/playlist.m3u8 → 200
GET /hls/clear/seg-000.ts → 200
GET /hls/clear/seg-000.ts + Range → 206
invalid Range → 416
unknown stream → 404
path traversal → rejected
```

Encrypted:

``` text
GET /hls/encrypted/playlist.m3u8 → 200
GET /hls/encrypted/key → 401
GET /hls/encrypted/key + valid token → 200
returned key length = 16
encrypted segment → 200
```

------------------------------------------------------------------------

# 37. Manual Validation

Use:

``` bash
curl -i http://localhost:8080/hls/clear/playlist.m3u8
```

Then:

``` bash
curl -i \
  http://localhost:8080/hls/clear/seg-000.ts
```

Range:

``` bash
curl -i \
  -H "Range: bytes=0-999" \
  http://localhost:8080/hls/clear/seg-000.ts
```

Expected:

``` text
HTTP/1.1 206 Partial Content
Content-Range: bytes 0-999/TOTAL
Accept-Ranges: bytes
Content-Length: 1000
```

Key:

``` bash
curl -i \
  http://localhost:8080/hls/encrypted/key
```

Expected:

``` text
401 Unauthorized
```

Then:

``` bash
curl -i \
  -H "X-Stream-Token: dev-secret" \
  http://localhost:8080/hls/encrypted/key
```

Expected:

``` text
200 OK
Content-Type: application/octet-stream
Content-Length: 16
```

------------------------------------------------------------------------

# 38. Keep-Alive Validation

Use one TCP connection and issue multiple requests.

Expected conceptual sequence:

``` text
TCP connection #1
    |
    +-- GET /hello
    |      200
    |
    +-- GET /hls/clear/playlist.m3u8
    |      200
    |
    +-- GET /hls/clear/seg-000.ts
    |      200
    |
    +-- GET /hls/clear/seg-001.ts
           200
```

Log the thread name and connection identity so reuse is visible.

------------------------------------------------------------------------

# 39. Thread Model

With:

``` text
workerThreads = 8
```

the server behaves approximately as:

``` text
acceptor thread
       |
       +---- client A -> worker-1
       +---- client B -> worker-2
       +---- client C -> worker-3
       +---- client D -> worker-4
       +---- client E -> worker-5
       +---- client F -> worker-6
       +---- client G -> worker-7
       +---- client H -> worker-8
```

The accept loop itself is sequential.

Connection processing is concurrent.

A keep-alive connection remains associated with its worker until the
connection closes or idle timeout fires.

------------------------------------------------------------------------

# 40. Benchmark Design

Two benchmark workloads.

## HTTP benchmark

Repeated:

``` text
GET /hello
```

Measure:

``` text
requests/sec
average latency
p50
p95
p99
```

Workers:

``` text
1
4
8
16
```

## Streaming benchmark

Simulated viewer:

``` text
GET playlist
       |
       +--> GET key
       |
       +--> GET segment 0
       |
       +--> GET segment 1
       |
       +--> GET segment 2
       |
       +--> ...
```

Run multiple viewers concurrently:

``` text
10
50
100
```

Record:

``` text
workers
concurrent viewers
total requests
requests/sec
average latency
p50
p95
p99
bytes served
errors
```

Never invent results.

------------------------------------------------------------------------

# 41. Benchmark Script Design

Pseudo-code:

``` python
async def simulate_viewer(base_url, encrypted):
    playlist = await get(
        f"{base_url}/hls/clear/playlist.m3u8"
    )

    if encrypted:
        key = await get(
            f"{base_url}/hls/encrypted/key",
            headers={"X-Stream-Token": TOKEN}
        )

    segments = parse_playlist(playlist)

    for segment in segments:
        await get(
            f"{base_url}{segment}"
        )
```

The actual benchmark should record timestamps around every request:

``` text
start = monotonic()
response = request(...)
end = monotonic()

latency = end - start
```

Then calculate:

``` text
p50
p95
p99
```

from the complete latency list.

------------------------------------------------------------------------

# 42. Three-Day Development Plan

## Day 1

### Developer A

Morning:

``` text
pom.xml
Main
ServerConfig
HttpRequest
HttpResponse
HttpStatus
HttpParser
ResponseWriter
```

Goal:

``` bash
curl localhost:8080/hello
```

Afternoon:

``` text
Router
Handler
RootHandler
HelloHandler
EchoHandler
StaticFileHandler
404
405
400
500
```

### Developer B

Morning:

``` text
sample.mp4
prepare-media.sh
```

Generate:

``` text
clear HLS
encrypted HLS
DASH
```

Verify with VLC/ffplay.

Afternoon:

``` text
HlsHandler
DashHandler
KeyHandler
MimeTypes
```

Initially serve complete files without Range.

### Day 1 Definition of Done

``` text
[ ] core server works
[ ] routing works
[ ] clear HLS files exist
[ ] encrypted HLS files exist
[ ] DASH files exist
[ ] handlers return correct MIME types
```

------------------------------------------------------------------------

# 43. Day 2

## Developer A

Morning:

``` text
ExecutorService
ClientHandler
thread logging
graceful shutdown
```

Afternoon:

``` text
MetricsRegistry
MetricsHandler
central metrics integration
```

## Developer B

Morning:

``` text
Range
RangeParser
FileRangeService
```

Apply to:

``` text
static
HLS
DASH
```

Afternoon:

``` text
KeyHandler auth
keep-alive integration
player.html
browser validation
```

### Day 2 Definition of Done

``` text
[ ] concurrent clients
[ ] keep-alive
[ ] idle timeout
[ ] 206 works
[ ] 416 works
[ ] clear HLS plays
[ ] encrypted HLS plays
[ ] key endpoint rejects invalid token
[ ] DASH plays
[ ] metrics works
```

------------------------------------------------------------------------

# 44. Day 3

## Morning --- Both

Integration:

``` text
clean checkout
mvn test
mvn package
prepare media
start server
run player
run tests
```

Then benchmarks:

``` text
1 worker
4 workers
8 workers
16 workers
```

if time permits.

Minimum:

``` text
1 worker
8 workers
```

## Afternoon

Developer A:

``` text
benchmark.py
results
performance explanation
concurrency discussion
README benchmark section
```

Developer B:

``` text
HLS architecture
DASH architecture
AES flow
player screenshots
limitations
future work
```

## Evening

Both:

``` text
README merge
cleanup
test clean checkout
git history cleanup
resume bullets
```

------------------------------------------------------------------------

# 45. Git Workflow

Create:

``` text
main
dev
feature/http-core
feature/streaming
feature/range
feature/metrics
feature/benchmark
```

Developer A:

``` bash
git checkout -b feature/http-core
```

Developer B:

``` bash
git checkout -b feature/streaming
```

Keep commits focused:

``` text
feat(http): add HTTP request parser
feat(server): add fixed worker pool
feat(router): add route matching
feat(streaming): add HLS handler
feat(streaming): add AES key endpoint
feat(http): add range parser
feat(metrics): add live counters
test(http): add parser tests
test(streaming): add HLS integration tests
```

Avoid giant commits such as:

``` text
"finished project"
```

------------------------------------------------------------------------

# 46. Important Design Decisions

## Decision 1 --- Offline media packaging

Do not implement:

``` text
MP4 -> HLS
```

inside Java.

FFmpeg handles this offline.

Reason:

``` text
server engineering
≠
video encoding engineering
```

------------------------------------------------------------------------

## Decision 2 --- Single rendition

Do not implement adaptive bitrate in the first version.

Use:

``` text
one playlist
one quality
```

This keeps the project focused on HTTP/server engineering.

------------------------------------------------------------------------

## Decision 3 --- AES-128 instead of DRM

Use:

``` text
AES-128 HLS
+
key endpoint
+
shared token
```

Do not claim DRM.

------------------------------------------------------------------------

## Decision 4 --- Shared range logic

One implementation:

``` text
FileRangeService
```

used by:

``` text
StaticFileHandler
HlsHandler
DashHandler
```

------------------------------------------------------------------------

## Decision 5 --- Fixed worker pool

Use:

``` java
Executors.newFixedThreadPool(workerThreads)
```

This makes worker-count benchmarking straightforward.

------------------------------------------------------------------------

# 47. Explicit Non-Goals

Do not implement during the three-day version:

``` text
WebRTC
Widevine
FairPlay DRM
PlayReady
HTTP/2
HTTPS/TLS termination
multi-range responses
adaptive bitrate
live HLS encoder
database
production authentication
CDN
transcoding
GUI/dashboard
distributed origin
```

These can be future work.

------------------------------------------------------------------------

# 48. Security Checklist

Minimum:

``` text
[ ] normalize paths
[ ] block ../ traversal
[ ] restrict HLS stream names
[ ] restrict filenames
[ ] validate Range
[ ] limit request headers
[ ] limit request body
[ ] validate Content-Length
[ ] validate AES key length
[ ] key endpoint requires token
[ ] do not expose stream.key through static serving
```

Very important:

``` text
media/hls-encrypted/stream.key
```

must never become reachable as:

``` text
/hls/encrypted/stream.key
```

The HLS handler should only allow expected media filenames, and the key
should only be accessible through `KeyHandler`.

------------------------------------------------------------------------

# 49. Final Architecture

``` text
                           INTERNET / BROWSER
                                  |
                                  v
                         +----------------+
                         |   TCP Socket   |
                         +-------+--------+
                                 |
                                 v
                         +---------------+
                         | ServerSocket  |
                         +-------+-------+
                                 |
                           accept()
                                 |
                                 v
                    +-------------------------+
                    |   Fixed Thread Pool     |
                    +-----------+-------------+
                                |
                                v
                    +-------------------------+
                    |     ClientHandler       |
                    |                         |
                    | keep-alive loop         |
                    +-----------+-------------+
                                |
                                v
                    +-------------------------+
                    |      HttpParser         |
                    +-----------+-------------+
                                |
                                v
                    +-------------------------+
                    |         Router          |
                    +---+---------+---------+-+
                        |         |         |
                        v         v         v
                     Static      HLS       DASH
                                  |
                         +--------+--------+
                         |        |        |
                         v        v        v
                      playlist  segment   key
                         |        |
                         |      Range
                         |        |
                         +--------+
                              |
                              v
                         File System

                         Metrics
                            ^
                            |
                      ClientHandler
```

------------------------------------------------------------------------

# 50. Definition of Done

``` text
HTTP
[ ] Server starts
[ ] TCP connections accepted
[ ] HTTP/1.1 requests parsed
[ ] GET works
[ ] POST works
[ ] headers work
[ ] Content-Length bodies work
[ ] 400 works
[ ] 404 works
[ ] 405 works
[ ] 500 works

Concurrency
[ ] fixed ExecutorService
[ ] configurable worker count
[ ] concurrent connections
[ ] thread names logged
[ ] graceful shutdown

Keep-alive
[ ] multiple requests on one TCP connection
[ ] Connection: close respected
[ ] HTTP/1.1 default keep-alive
[ ] idle timeout
[ ] Content-Length always supplied

Files
[ ] static files
[ ] traversal blocked
[ ] MIME detection

Range
[ ] bytes=start-end
[ ] bytes=start-
[ ] bytes=-suffix
[ ] 206
[ ] 416
[ ] Accept-Ranges
[ ] Content-Range
[ ] shared implementation

HLS
[ ] clear playlist served
[ ] clear segments served
[ ] correct MIME types
[ ] encrypted playlist served
[ ] encrypted segments served
[ ] AES-128 key endpoint
[ ] unauthorized key request -> 401
[ ] authorized key request -> 200
[ ] browser playback works

DASH
[ ] MPD served
[ ] init segment served
[ ] media segments served
[ ] correct MIME types
[ ] range support

Metrics
[ ] request count
[ ] status counts
[ ] active connections
[ ] bytes served
[ ] uptime
[ ] live endpoint

Testing
[ ] parser tests
[ ] router tests
[ ] range tests
[ ] static file tests
[ ] streaming tests
[ ] keep-alive validation
[ ] key auth validation
[ ] browser validation

Benchmark
[ ] HTTP benchmark
[ ] streaming benchmark
[ ] 1 worker
[ ] 8 workers
[ ] optionally 4/16 workers
[ ] concurrency 10/50/100
[ ] average latency
[ ] p50
[ ] p95
[ ] p99
[ ] requests/sec
[ ] saved raw results

Documentation
[ ] architecture
[ ] setup
[ ] media preparation
[ ] API routes
[ ] HLS flow
[ ] AES flow
[ ] Range flow
[ ] keep-alive tradeoff
[ ] benchmark methodology
[ ] real benchmark numbers only
[ ] limitations
[ ] future work
```

------------------------------------------------------------------------

# 51. Recommended Implementation Order

Do **not** start by writing all the classes above.

Use this exact dependency order:

``` text
1. ServerConfig
       ↓
2. HttpRequest / HttpResponse / HttpStatus
       ↓
3. HttpParser
       ↓
4. ResponseWriter
       ↓
5. Handler
       ↓
6. Router
       ↓
7. ClientHandler
       ↓
8. HttpServer
       ↓
9. Hello/Echo
       ↓
10. StaticFileHandler
       ↓
11. RangeParser
       ↓
12. FileRangeService
       ↓
13. HlsHandler
       ↓
14. KeyHandler
       ↓
15. DashHandler
       ↓
16. keep-alive
       ↓
17. metrics
       ↓
18. player.html
       ↓
19. integration tests
       ↓
20. benchmark
```

The key architectural rule is:

> **First make HTTP work. Then make file delivery correct. Then add
> Range and keep-alive. Only then layer HLS encryption and DASH on
> top.**

That gives both developers parallel work without creating circular
dependencies.
