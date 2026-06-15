package org.example.core.model;

import java.nio.file.Path;
import java.util.List;

public record MergeOptions(List<PageSource> sources, Path output) {
    public MergeOptions {
        sources = List.copyOf(sources);
    }
}
