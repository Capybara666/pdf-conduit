package org.example.core.model;

import java.nio.file.Path;

public record CompressOptions(Path input, long targetSizeBytes, Path output) {}
