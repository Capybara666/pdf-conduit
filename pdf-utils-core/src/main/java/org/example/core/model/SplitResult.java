package org.example.core.model;

import java.nio.file.Path;

public record SplitResult(Path output, int pageCount) {}
