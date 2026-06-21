package com.pdfconduit.core.model;

import java.nio.file.Path;

public record MergeResult(Path output, int pageCount) {}
