package com.pdfconduit.core.model;

import java.nio.file.Path;

public record CompressResult(Path output, long originalBytes, long resultBytes, boolean targetReached) {}
