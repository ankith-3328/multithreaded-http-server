package com.ankith.httpserver.http;

import com.ankith.httpserver.http.Range;
import com.ankith.httpserver.http.RangeParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RangeParserTest {

    private static final long FILE_SIZE = 1000;

    @Test
    void parsesStartEndRange() {
        Range range = RangeParser.parse("bytes=100-199", FILE_SIZE);
        assertEquals(100, range.start());
        assertEquals(199, range.end());
        assertEquals(100, range.length());
    }

    @Test
    void parsesOpenEndedRange() {
        Range range = RangeParser.parse("bytes=900-", FILE_SIZE);
        assertEquals(900, range.start());
        assertEquals(999, range.end());
    }

    @Test
    void parsesSuffixRange() {
        Range range = RangeParser.parse("bytes=-500", FILE_SIZE);
        assertEquals(500, range.start());
        assertEquals(999, range.end());
    }

    @Test
    void clampsEndToFileSize() {
        Range range = RangeParser.parse("bytes=0-99999", FILE_SIZE);
        assertEquals(999, range.end());
    }

    @Test
    void rejectsStartBeyondFileSize() {
        assertThrows(IllegalArgumentException.class, () ->
                RangeParser.parse("bytes=5000-6000", FILE_SIZE));
    }

    @Test
    void rejectsMultiRange() {
        assertThrows(IllegalArgumentException.class, () ->
                RangeParser.parse("bytes=0-100,200-300", FILE_SIZE));
    }

    @Test
    void rejectsMissingBytesPrefix() {
        assertThrows(IllegalArgumentException.class, () ->
                RangeParser.parse("100-199", FILE_SIZE));
    }
}
