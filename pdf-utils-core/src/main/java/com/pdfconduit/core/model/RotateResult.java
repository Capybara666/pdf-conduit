package com.pdfconduit.core.model;

import java.nio.file.Path;

public record RotateResult(Path output, int rotatedPageCount) {}
