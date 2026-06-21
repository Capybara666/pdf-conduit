package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Options for exporting a PDF's pages as raster images.
 *
 * @param input        the source PDF
 * @param format       PNG or JPEG
 * @param dpi          render resolution (dots per inch)
 * @param pages        which pages to export ({@link PageRange#ALL} for every page)
 * @param jpegQuality  JPEG quality 0..1 (ignored for PNG)
 * @param outputDir    folder the images are written into
 * @param baseName     file-name stem; each image is {@code <baseName>_pNNN.<ext>}
 */
public record PdfToImageOptions(Path input, ImageFormat format, int dpi, PageRange pages,
                                float jpegQuality, Path outputDir, String baseName) {}
