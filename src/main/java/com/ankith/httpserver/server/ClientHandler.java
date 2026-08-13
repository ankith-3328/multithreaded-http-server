package com.ankith.httpserver.server;

import com.ankith.httpserver.config.ServerConfig;
import com.ankith.httpserver.http.*;
import com.ankith.httpserver.logging.RequestLogger;
import com.ankith.httpserver.metrics.MetricsRegistry;
import com.ankith.httpserver.router.Router;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;

public final class ClientHandler implements Runnable {

    private final Socket socket;
    private final Router router;
    private final ServerConfig config;
    private final MetricsRegistry metrics;

    public ClientHandler(
            Socket socket,
            Router router,
            ServerConfig config,
            MetricsRegistry metrics
    ) {
        this.socket = socket;
        this.router = router;
        this.config = config;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        String thread = Thread.currentThread().getName();
        metrics.connectionOpened();

        try (socket) {

            socket.setSoTimeout(config.keepAliveTimeoutMs());

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
                    HttpResponse errorResponse = HttpResponse.text(
                            HttpStatus.BAD_REQUEST,
                            bad.getMessage() + "\n"
                    );
                    writer.write(output, errorResponse);
                    metrics.recordRequest(400, errorResponse.body().length);
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

                boolean keepAlive = KeepAliveManager.shouldKeepAlive(request);

                response = KeepAliveManager.withConnectionHeader(response, keepAlive);

                writer.write(output, response);

                metrics.recordRequest(
                        response.status().code(),
                        response.body().length
                );
                RequestLogger.log(request, response);

                if (!keepAlive) {
                    break;
                }
            }

        } catch (IOException e) {
            System.err.println(thread + " connection error: " + e.getMessage());
        } finally {
            metrics.connectionClosed();
        }
    }
}