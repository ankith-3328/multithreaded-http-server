package com.ankith.httpserver.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public final class MetricsRegistry {

    private final LongAdder requestsTotal = new LongAdder();

    // status code -> count, thread-safe map of thread-safe counters
    private final Map<Integer, LongAdder> statusCounts = new ConcurrentHashMap<>();

    private final LongAdder bytesServed = new LongAdder();

    private final AtomicLong activeConnections = new AtomicLong();

    private final long startTime = System.nanoTime();

    public void recordRequest(int status, long bytes) {
        requestsTotal.increment();
        bytesServed.add(bytes);

        statusCounts
                .computeIfAbsent(status, s -> new LongAdder())
                .increment();
    }

    public long requestsTotal() {
        return requestsTotal.sum();
    }

    public long statusCount(int status) {
        LongAdder adder = statusCounts.get(status);
        return adder == null ? 0 : adder.sum();
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
        return (System.nanoTime() - startTime) / 1_000_000_000L;
    }
}
