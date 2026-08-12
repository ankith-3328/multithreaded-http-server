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