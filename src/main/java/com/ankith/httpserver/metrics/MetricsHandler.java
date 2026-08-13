package com.ankith.httpserver.metrics;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;
import com.ankith.httpserver.http.HttpStatus;
import com.ankith.httpserver.router.Handler;

public final class MetricsHandler implements Handler {

    private final MetricsRegistry metrics;

    public MetricsHandler(MetricsRegistry metrics) {
        this.metrics = metrics;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        StringBuilder body = new StringBuilder();

        body.append("requests_total ").append(metrics.requestsTotal()).append("\n");
        body.append("requests_by_status_200 ").append(metrics.statusCount(200)).append("\n");
        body.append("requests_by_status_206 ").append(metrics.statusCount(206)).append("\n");
        body.append("requests_by_status_404 ").append(metrics.statusCount(404)).append("\n");
        body.append("active_connections ").append(metrics.activeConnections()).append("\n");
        body.append("bytes_served_total ").append(metrics.bytesServed()).append("\n");
        body.append("uptime_seconds ").append(metrics.uptimeSeconds()).append("\n");

        return HttpResponse.text(HttpStatus.OK, body.toString());
    }
}
