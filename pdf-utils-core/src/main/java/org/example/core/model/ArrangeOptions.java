package org.example.core.model;

import java.nio.file.Path;
import java.util.List;

/**
 * Reorder the pages of {@code input} into {@code order} (1-indexed page numbers,
 * order preserved, duplicates allowed, omissions allowed) and write {@code output}.
 * An empty {@code order} keeps the document's natural page order.
 */
public record ArrangeOptions(Path input, List<Integer> order, Path output) {
    public ArrangeOptions {
        order = List.copyOf(order);
    }
}
