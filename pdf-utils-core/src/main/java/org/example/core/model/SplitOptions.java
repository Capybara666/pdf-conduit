package org.example.core.model;

import java.nio.file.Path;

public record SplitOptions(Path input, PageRange pages, Path output) {}
