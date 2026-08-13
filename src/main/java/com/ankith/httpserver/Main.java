package com.ankith.httpserver;

import com.ankith.httpserver.config.ServerConfig;
import com.ankith.httpserver.handler.*;
import com.ankith.httpserver.metrics.*;
import com.ankith.httpserver.router.Router;
import com.ankith.httpserver.server.HttpServer;
import com.ankith.httpserver.streaming.DashHandler;
import com.ankith.httpserver.streaming.FileRangeService;
import com.ankith.httpserver.streaming.HlsHandler;
import com.ankith.httpserver.streaming.KeyHandler;

import java.nio.file.Path;

/**
 * Route order matters: the router is prefix-based first-match, so
 * /hls/encrypted/key MUST be registered before /hls/ or the key
 * endpoint would be swallowed by HlsHandler.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.defaults();

        FileRangeService rangeService = new FileRangeService();
        MetricsRegistry metrics = new MetricsRegistry();
        Router router = new Router();

        router.get("/hello", new HelloHandler());
        router.post("/echo", new EchoHandler());

        router.get(
                "/player/",
                new StaticFileHandler(Path.of(config.staticDirectory()), rangeService)
        );

        router.get(
                "/hls/encrypted/key",
                new KeyHandler(
                        Path.of(config.mediaDirectory(), "hls-encrypted", "stream.key"),
                        config.streamToken()
                )
        );

        router.get(
                "/hls/",
                new HlsHandler(Path.of(config.mediaDirectory()), rangeService)
        );

        router.get(
                "/dash/",
                new DashHandler(Path.of(config.mediaDirectory(), "dash"), rangeService)
        );

        router.get("/metrics", new MetricsHandler(metrics));

        router.get("/", new RootHandler());

        HttpServer server = new HttpServer(config, router, metrics);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        server.start();
    }
}