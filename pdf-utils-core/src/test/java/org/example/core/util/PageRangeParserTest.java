package org.example.core.util;

import org.example.core.exception.InvalidPageRangeException;
import org.example.core.model.PageRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageRangeParserTest {

    @Test
    void parseSinglePage() throws InvalidPageRangeException {
        PageRange r = PageRangeParser.parse("3", 10);
        assertEquals(List.of(3), r.pageNumbers());
    }

    @Test
    void parseRange() throws InvalidPageRangeException {
        PageRange r = PageRangeParser.parse("2-5", 10);
        assertEquals(List.of(2, 3, 4, 5), r.pageNumbers());
    }

    @Test
    void parseCommaList() throws InvalidPageRangeException {
        PageRange r = PageRangeParser.parse("1,3,5", 10);
        assertEquals(List.of(1, 3, 5), r.pageNumbers());
    }

    @Test
    void parseMixed() throws InvalidPageRangeException {
        PageRange r = PageRangeParser.parse("1,3-5,7", 10);
        assertEquals(List.of(1, 3, 4, 5, 7), r.pageNumbers());
    }

    @Test
    void parseEndMinus() throws InvalidPageRangeException {
        PageRange r = PageRangeParser.parse("end-2", 10);
        assertEquals(List.of(8), r.pageNumbers());
    }

    @Test
    void parseEnd() throws InvalidPageRangeException {
        PageRange r = PageRangeParser.parse("end", 10);
        assertEquals(List.of(10), r.pageNumbers());
    }

    @Test
    void outOfRangeThrows() {
        assertThrows(InvalidPageRangeException.class, () -> PageRangeParser.parse("15", 10));
    }

    @Test
    void invalidExpressionThrows() {
        assertThrows(InvalidPageRangeException.class, () -> PageRangeParser.parse("abc", 10));
    }

    @Test
    void deduplicatesAndSorts() throws InvalidPageRangeException {
        PageRange r = PageRangeParser.parse("3,1,2,1", 10);
        assertEquals(List.of(1, 2, 3), r.pageNumbers());
    }
}
