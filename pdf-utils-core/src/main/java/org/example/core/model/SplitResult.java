package org.example.core.model;

import java.nio.file.Path;
import java.util.List;

/**
 * Files produced by a split. {@code outputs} has one entry for a combined result
 * and one per page for a separate-files result; {@code pageCount} is the total
 * number of pages written across all of them.
 */
public record SplitResult(List<Path> outputs, int pageCount) {

    public SplitResult {
        outputs = List.copyOf(outputs);
    }

    /** The first (and, for a combined result, only) output file. */
    public Path output() {
        return outputs.get(0);
    }

    public int fileCount() {
        return outputs.size();
    }
}
