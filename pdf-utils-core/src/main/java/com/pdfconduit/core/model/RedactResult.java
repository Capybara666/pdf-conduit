package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Outcome of a redaction: where it was written, how many pages were rasterised
 * (i.e. had at least one region) and how many regions were painted in total.
 */
public record RedactResult(Path output, int redactedPages, int redactedRegions) {}
