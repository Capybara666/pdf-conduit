package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Options for exporting a PDF's text content.
 *
 * @param input      the source PDF
 * @param format     TXT (raw PDFBox extraction) or DOCX (structured OOXML, built in memory)
 * @param pages      pages to extract ({@link PageRange#ALL} for all); applies to both formats
 * @param outputDir  folder the result is written into
 * @param baseName   file-name stem; the output is {@code <baseName>.<ext>}
 */
public record PdfToTextOptions(Path input, TextFormat format, PageRange pages,
                               Path outputDir, String baseName) {}
