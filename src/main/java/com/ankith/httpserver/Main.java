package com.ankith.httpserver;

import com.ankith.httpserver.config.ServerConfig;
import com.ankith.httpserver.handler.EchoHandler;
import com.ankith.httpserver.handler.HelloHandler;
import com.ankith.httpserver.router.Router;
import com.ankith.httpserver.server.HttpServer;
import com.ankith.httpserver.streaming.DashHandler;
import com.ankith.httpserver.streaming.FileRangeService;
import com.ankith.httpserver.streaming.HlsHandler;
import com.ankith.httpserver.streaming.KeyHandler;

import java.nio.file.Path;

/**
 * Wires config, router and handlers, then starts the server.
 *
 * Route order matters: the router is prefix-based first-match, so
 * /hls/encrypted/key MUST be registered before /hls/ or the key
 * endpoint would be swallowed by HlsHandler.
 */
public final class Main {

    public static void main(String[] args) throws Exception {

        ServerConfig config = ServerConfig.defaults();

        // Optional: override port via first CLI arg, e.g. "8082".
        // Useful for local testing and benchmark runs.
        if (args.length > 0) {
            config = new ServerConfig(
                    Integer.parseInt(args[0]),
                    config.workerThreads(),
                    config.staticDirectory(),
                    config.mediaDirectory(),
                    config.keepAliveTimeoutMs(),
                    config.metricsEnabled(),
                    config.streamToken()
            );
        }

        FileRangeService rangeService = new FileRangeService();

        Router router = new Router();

        // Base API
        router.get("/hello", new HelloHandler());
        router.post("/echo", new EchoHandler());

        // Key endpoint FIRST: /hls/ prefix would otherwise match it.
        router.get(
                "/hls/encrypted/key",
                new KeyHandler(
                        Path.of(config.mediaDirectory(),
                                "hls-encrypted", "stream.key"),
                        config.streamToken()
                )
        );

        // HLS (clear + encrypted)
        router.get(
                "/hls/",
                new HlsHandler(
                        Path.of(config.mediaDirectory()),
                        rangeService
                )
        );

        // DASH
        router.get(
                "/dash/",
                new DashHandler(
                        Path.of(config.mediaDirectory(), "dash"),
                        rangeService
                )
        );

        HttpServer server = new HttpServer(config, router);

        Runtime.getRuntime().addShutdownHook(
                new Thread(server::stop)
        );

        server.start();
    }
}
