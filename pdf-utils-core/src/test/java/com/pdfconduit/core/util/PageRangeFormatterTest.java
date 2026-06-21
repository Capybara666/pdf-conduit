package com.pdfconduit.core.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageRangeFormatterTest {

    @Test
    void emptyIsBlank() {
        assertEquals("", PageRangeFormatter.format(List.of()));
        assertEquals("", PageRangeFormatter.format(null));
    }

    @Test
    void singlePage() {
        assertEquals("5", PageRangeFormatter.format(List.of(5)));
    }

    @Test
    void contiguousBecomesRange() {
        assertEquals("1-3", PageRangeFormatter.format(List.of(1, 2, 3)));
    }

    @Test
    void mixedRangesAndSingles() {
        assertEquals("1-3,5,8-9", PageRangeFormatter.format(List.of(1, 2, 3, 5, 8, 9)));
    }

    @Test
    void sortsAndDeduplicates() {
        assertEquals("1-3", PageRangeFormatter.format(List.of(3, 1, 2, 1)));
    }

    @Test
    void roundTripsWithParser() throws Exception {
        String expr = "1-3,5,8-9";
        var parsed = PageRangeParser.parse(expr, 10).pageNumbers();
        assertEquals(expr, PageRangeFormatter.format(parsed));
    }
}
