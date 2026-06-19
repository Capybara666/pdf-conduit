package org.example.core.util;

import java.util.List;
import java.util.TreeSet;

/**
 * Formats a list of 1-indexed page numbers into the compact range syntax
 * understood by {@link PageRangeParser} — e.g. {@code [1,2,3,5,8,9]} →
 * {@code "1-3,5,8-9"}. The input is sorted and de-duplicated (it describes a
 * <em>set</em> of pages, matching extract/rotate semantics). An empty list
 * yields {@code ""} (the convention for "all pages").
 */
public final class PageRangeFormatter {

    private PageRangeFormatter() {}

    public static String format(List<Integer> pages) {
        if (pages == null || pages.isEmpty()) return "";
        TreeSet<Integer> sorted = new TreeSet<>(pages);
        StringBuilder sb = new StringBuilder();
        Integer start = null, prev = null;
        for (int n : sorted) {
            if (start == null) { start = prev = n; continue; }
            if (n == prev + 1) { prev = n; continue; }
            append(sb, start, prev);
            start = prev = n;
        }
        append(sb, start, prev);
        return sb.toString();
    }

    private static void append(StringBuilder sb, int start, int end) {
        if (sb.length() > 0) sb.append(',');
        sb.append(end > start ? start + "-" + end : Integer.toString(start));
    }
}
