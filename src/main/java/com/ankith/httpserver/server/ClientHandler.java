package com.ankith.httpserver.server;

import com.ankith.httpserver.config.ServerConfig;
import com.ankith.httpserver.http.*;
import com.ankith.httpserver.router.Router;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;

public final class ClientHandler implements Runnable {

    private final Socket socket;
    private final Router router;
    private final ServerConfig config;

    public ClientHandler(
            Socket socket,
            Router router,
            ServerConfig config
    ) {
        this.socket = socket;
        this.router = router;
        this.config = config;
    }

    @Override
    public void run() {
        String thread = Thread.currentThread().getName();

        try (socket) {

            socket.setSoTimeout(
                    config.keepAliveTimeoutMs()
            );

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
                    writer.write(
                            output,
                            HttpResponse.text(
                                    HttpStatus.BAD_REQUEST,
                                    bad.getMessage()
                            )
                    );
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

                boolean keepAlive =
                        KeepAliveManager.shouldKeepAlive(request);

                response = KeepAliveManager.withConnectionHeader(
                        response,
                        keepAlive
                );

                writer.write(output, response);

                if (!keepAlive) {
                    break;
                }
            }

        } catch (IOException e) {
            System.err.println(
                    thread + " connection error: "
                            + e.getMessage()
            );
        }
    }
}