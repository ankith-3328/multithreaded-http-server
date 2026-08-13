package com.ankith.httpserver.router;

import com.ankith.httpserver.http.HttpRequest;
import com.ankith.httpserver.http.HttpResponse;
import com.ankith.httpserver.http.HttpStatus;
import com.ankith.httpserver.router.Router;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouterTest {

    private HttpRequest request(String method, String target) {
        return new HttpRequest(method, target, "HTTP/1.1", Map.of(), new byte[0]);
    }

    @Test
    void routesMatchingGet() {
        Router router = new Router();
        router.get("/hello", req -> HttpResponse.text(HttpStatus.OK, "hi\n"));

        HttpResponse response = router.route(request("GET", "/hello"));

        assertEquals(HttpStatus.OK, response.status());
    }

    @Test
    void returns404ForUnknownRoute() {
        Router router = new Router();
        router.get("/hello", req -> HttpResponse.text(HttpStatus.OK, "hi\n"));

        HttpResponse response = router.route(request("GET", "/does-not-exist"));

        assertEquals(HttpStatus.NOT_FOUND, response.status());
    }

    @Test
    void returns405WhenPathExistsButMethodDoesNot() {
        Router router = new Router();
        router.get("/hello", req -> HttpResponse.text(HttpStatus.OK, "hi\n"));

        HttpResponse response = router.route(request("POST", "/hello"));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.status());
    }

    @Test
    void prefixMatchingWorksForStaticStyleRoutes() {
        Router router = new Router();
        router.get("/hls/", req -> HttpResponse.text(HttpStatus.OK, "segment\n"));

        HttpResponse response = router.route(request("GET", "/hls/clear/playlist.m3u8"));

        assertEquals(HttpStatus.OK, response.status());
    }
}
