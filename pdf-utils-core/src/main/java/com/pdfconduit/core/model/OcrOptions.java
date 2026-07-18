package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Options for OCR-ing a PDF into a searchable PDF: every page is rendered to an image, run
 * through the {@code tesseract} binary, and an invisible text layer is drawn over the original
 * page so the visual output is unchanged but the recognised words become selectable/extractable.
 *
 * @param input     the source PDF (non-PDF inputs are routed to PDF by the calling surface first)
 * @param languages tesseract language codes ({@code -l}), e.g. {@code eng} or {@code eng+deu};
 *                  blank falls back to the process default
 * @param dpi       render resolution used to rasterise each page for recognition (clamped)
 * @param output    where the searchable PDF is written
 */
public record OcrOptions(Path input, String languages, int dpi, Path output) {}
