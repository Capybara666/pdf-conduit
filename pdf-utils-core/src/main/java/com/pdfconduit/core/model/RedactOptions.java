package com.pdfconduit.core.model;

import java.nio.file.Path;
import java.util.List;

/**
 * Inputs for {@link com.pdfconduit.core.operations.PdfRedactor}: the source PDF,
 * the rectangles to black out (see {@link RedactRegion}), the render DPI used for
 * the rasterised pages ({@code <= 0} ⇒ a sensible default) and where to write.
 */
public record RedactOptions(Path input, List<RedactRegion> regions, int dpi, Path output) {}
