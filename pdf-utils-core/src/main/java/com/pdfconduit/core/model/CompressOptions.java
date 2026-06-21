package com.pdfconduit.core.model;

import java.nio.file.Path;

public record CompressOptions(Path input, long targetSizeBytes, Path output) {}
