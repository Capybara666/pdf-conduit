package org.example.core.util;

import org.example.core.exception.InvalidPageRangeException;
import org.example.core.model.PageRange;
import java.util.ArrayList;
import java.util.TreeSet;

public class PageRangeParser {

    public static PageRange parse(String expression, int totalPages) throws InvalidPageRangeException {
        if (expression == null || expression.isBlank()) throw new InvalidPageRangeException(expression);
        TreeSet<Integer> pages = new TreeSet<>();
        for (String segment : expression.split(",")) {
            segment = segment.strip();
            if (segment.contains("-") && !segment.startsWith("end")) {
                String[] parts = segment.split("-", 2);
                int from = resolveNumber(parts[0].strip(), totalPages, expression);
                int to   = resolveNumber(parts[1].strip(), totalPages, expression);
                if (from > to) throw new InvalidPageRangeException(expression);
                for (int i = from; i <= to; i++) pages.add(i);
            } else {
                pages.add(resolveNumber(segment, totalPages, expression));
            }
        }
        return new PageRange(new ArrayList<>(pages));
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
