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
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        }
        if (path.endsWith(".key")) {
            return "application/octet-stream";
        }
        return "application/octet-stream";
    }
}
