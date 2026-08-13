package com.ankith.httpserver.streaming;

import com.ankith.httpserver.http.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared by StaticFileHandler, HlsHandler and DashHandler.
 * NOTE (owned by Dev B, stubbed here by Dev A so Day-1 static file
 * serving compiles and runs before streaming work begins).
 *
 * Known limitation: uses Files.readAllBytes(), which loads the whole
 * file into memory. Fine for small static files & short segments in
 * this project's scope; swap for a streamed write for large media if
 * time allows (see design doc section 21).
 */
public final class FileRangeService {

    public HttpResponse serve(Path file, String contentType, String rangeHeader) {
        try {
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                return HttpResponse.text(HttpStatus.NOT_FOUND, "File not found\n");
            }

            long size = Files.size(file);

            if (rangeHeader == null) {
                byte[] body = Files.readAllBytes(file);

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Type", contentType);
                headers.put("Accept-Ranges", "bytes");

                return new HttpResponse(HttpStatus.OK, headers, body);
            }

            Range range;

            try {
                range = RangeParser.parse(rangeHeader, size);
            } catch (IllegalArgumentException e) {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Range", "bytes */" + size);

                return new HttpResponse(HttpStatus.RANGE_NOT_SATISFIABLE, headers, new byte[0]);
            }

            byte[] full = Files.readAllBytes(file);

            int length = Math.toIntExact(range.length());
            byte[] body = new byte[length];

            System.arraycopy(full, Math.toIntExact(range.start()), body, 0, length);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", contentType);
            headers.put("Accept-Ranges", "bytes");
            headers.put("Content-Range",
                    "bytes " + range.start() + "-" + range.end() + "/" + size);

            return new HttpResponse(HttpStatus.PARTIAL_CONTENT, headers, body);

        } catch (IOException e) {
            return HttpResponse.text(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read file\n");
        }
    }
}
