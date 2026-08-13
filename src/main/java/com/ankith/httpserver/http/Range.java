package com.ankith.httpserver.http;

public record Range(long start, long end) {

    public long length() {
        return end - start + 1;
    }
}
