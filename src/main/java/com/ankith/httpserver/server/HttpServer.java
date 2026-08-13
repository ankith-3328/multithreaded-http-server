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