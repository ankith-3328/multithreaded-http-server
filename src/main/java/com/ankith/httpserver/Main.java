package com.ankith.httpserver;

import com.ankith.httpserver.http.*;

import java.net.ServerSocket;
import java.net.Socket;

public class Main {

    public static void main(String[] args) throws Exception {

        ServerSocket server = new ServerSocket(8080);

        HttpParser parser = new HttpParser();
        ResponseWriter writer = new ResponseWriter();

        while (true) {
            Socket socket = server.accept();

            try (socket) {
                HttpRequest request =
                        parser.parse(socket.getInputStream());

                HttpResponse response =
                        HttpResponse.text(
                                HttpStatus.OK,
                                "Hello World"
                        );

                writer.write(
                        socket.getOutputStream(),
                        response
                );
            }
        }
    }
}