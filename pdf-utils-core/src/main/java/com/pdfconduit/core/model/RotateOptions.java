package com.pdfconduit.core.model;

import java.nio.file.Path;

public record RotateOptions(Path input, PageRange pages, int angleDegrees, Path output) {}
