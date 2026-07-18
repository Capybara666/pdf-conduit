package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Outcome of an OCR pass: where the searchable PDF was written, how many pages were processed,
 * and how many words were placed into the invisible text layer across all pages.
 */
public record OcrResult(Path output, int pages, int words) {}
