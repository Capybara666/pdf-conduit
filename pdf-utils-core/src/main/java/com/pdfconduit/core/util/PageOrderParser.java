package com.pdfconduit.core.util;

import com.pdfconduit.core.exception.InvalidPageRangeException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a page <em>ordering</em> expression into an explicit, order-preserving
 * list of 1-indexed page numbers.
 *
 * <p>Unlike {@link PageRangeParser} — which sorts and de-duplicates because it
 * describes a <em>set</em> of pages to extract — this parser keeps the order the
 * user wrote and allows a page to appear more than once (so a page can be moved,
 * duplicated, or dropped). Ranges may run forwards or backwards:
 * <ul>
 *   <li>{@code 3,1,2} → {@code [3, 1, 2]}</li>
 *   <li>{@code 1-3} → {@code [1, 2, 3]}</li>
 *   <li>{@code 5-1} → {@code [5, 4, 3, 2, 1]} (reverse)</li>
 *   <li>{@code 1,1,2} → {@code [1, 1, 2]} (duplicate kept)</li>
 *   <li>{@code end}, {@code end-1} resolve against the page count</li>
 * </ul>
 * A blank expression yields the natural order {@code [1 … total]}.
 */
public final class PageOrderParser {

    private PageOrderParser() {}

    public static List<Integer> parse(String expression, int totalPages)
            throws InvalidPageRangeException {
        if (expression == null || expression.isBlank()) {
            List<Integer> all = new ArrayList<>(totalPages);
            for (int i = 1; i <= totalPages; i++) all.add(i);
            return all;
        }
        List<Integer> order = new ArrayList<>();
        for (String segment : expression.split(",")) {
            segment = segment.strip();
            if (segment.isEmpty()) continue;
            if (segment.contains("-") && !segment.startsWith("end")) {
                String[] parts = segment.split("-", 2);
                int from = resolveNumber(parts[0].strip(), totalPages, expression);
                int to   = resolveNumber(parts[1].strip(), totalPages, expression);
                if (from <= to) {
                    for (int i = from; i <= to; i++) order.add(i);
                } else {
                    for (int i = from; i >= to; i--) order.add(i);
                }
            } else {
                order.add(resolveNumber(segment, totalPages, expression));
            }
        }
        if (order.isEmpty()) throw new InvalidPageRangeException(expression);
        return order;
    }

    private static int resolveNumber(String token, int totalPages, String expr)
            throws InvalidPageRangeException {
        try {
            int n;
            if (token.startsWith("end-")) {
                n = totalPages - Integer.parseInt(token.substring(4));
            } else if (token.equals("end")) {
                n = totalPages;
            } else {
                n = Integer.parseInt(token);
            }
            if (n < 1 || n > totalPages) throw new InvalidPageRangeException(expr);
            return n;
        } catch (NumberFormatException e) {
            throw new InvalidPageRangeException(expr);
        }
    }
}
