package com.pdfconduit.core.util;

import com.pdfconduit.core.exception.InvalidPageRangeException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageOrderParserTest {

    @Test
    void blankKeepsNaturalOrder() throws Exception {
        assertEquals(List.of(1, 2, 3, 4), PageOrderParser.parse("", 4));
        assertEquals(List.of(1, 2, 3, 4), PageOrderParser.parse(null, 4));
    }

    @Test
    void preservesWrittenOrder() throws Exception {
        assertEquals(List.of(3, 1, 2), PageOrderParser.parse("3,1,2", 3));
    }

    @Test
    void ascendingRange() throws Exception {
        assertEquals(List.of(2, 3, 4), PageOrderParser.parse("2-4", 10));
    }

    @Test
    void descendingRangeReverses() throws Exception {
        assertEquals(List.of(5, 4, 3, 2, 1), PageOrderParser.parse("5-1", 10));
    }

    @Test
    void duplicatesAreKept() throws Exception {
        assertEquals(List.of(1, 1, 2), PageOrderParser.parse("1,1,2", 3));
    }

    @Test
    void mixedTokens() throws Exception {
        assertEquals(List.of(4, 1, 2, 3), PageOrderParser.parse("end,1-3", 4));
    }

    @Test
    void endMinus() throws Exception {
        assertEquals(List.of(8), PageOrderParser.parse("end-2", 10));
    }

    @Test
    void outOfRangeThrows() {
        assertThrows(InvalidPageRangeException.class, () -> PageOrderParser.parse("11", 10));
    }

    @Test
    void garbageThrows() {
        assertThrows(InvalidPageRangeException.class, () -> PageOrderParser.parse("abc", 10));
    }
}
