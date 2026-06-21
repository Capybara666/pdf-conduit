package com.pdfconduit.core.model;

import java.nio.file.Path;

public record PdfResult(Path output, int pageCount) {}
