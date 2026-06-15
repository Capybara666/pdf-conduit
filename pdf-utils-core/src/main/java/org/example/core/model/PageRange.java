package org.example.core.model;

import java.util.List;

/**
 * Resolved list of 1-indexed page numbers. Empty list means "all pages".
 */
public record PageRange(List<Integer> pageNumbers) {
    public static final PageRange ALL = new PageRange(List.of());

    public PageRange {
        pageNumbers = List.copyOf(pageNumbers);
    }

    public boolean isAll() {
        return pageNumbers.isEmpty();
    }
}
