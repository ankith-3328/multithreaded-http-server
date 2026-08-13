package com.ankith.httpserver.http;

public final class RangeParser {

    private RangeParser() {}

    public static Range parse(
            String header,
            long fileSize
    ) {
        if (header == null
                || !header.startsWith("bytes=")) {
            throw new IllegalArgumentException("Invalid Range");
        }

        String value = header.substring(6);

        if (value.contains(",")) {
            throw new IllegalArgumentException(
                    "Multi-range unsupported"
            );
        }

        String[] parts = value.split("-", -1);

        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid Range");
        }

        long start;
        long end;

        try {
            if (parts[0].isEmpty()) {
                long suffixLength = Long.parseLong(parts[1]);

                if (suffixLength <= 0) {
                    throw new IllegalArgumentException();
                }

                start = Math.max(
                        0,
                        fileSize - suffixLength
                );

                end = fileSize - 1;

            } else {
                start = Long.parseLong(parts[0]);

                if (parts[1].isEmpty()) {
                    end = fileSize - 1;
                } else {
                    end = Long.parseLong(parts[1]);
                }
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid Range");
        }

        if (start < 0
                || start >= fileSize
                || end < start) {
            throw new IllegalArgumentException(
                    "Range Not Satisfiable"
            );
        }

        end = Math.min(end, fileSize - 1);

        return new Range(start, end);
    }
}
