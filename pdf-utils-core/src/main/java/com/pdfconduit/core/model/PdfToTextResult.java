package com.pdfconduit.core.model;

import java.nio.file.Path;

/** Result of a text export: the single file written. */
public record PdfToTextResult(Path output) {}
