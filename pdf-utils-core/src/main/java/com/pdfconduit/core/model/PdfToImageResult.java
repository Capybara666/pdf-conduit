package com.pdfconduit.core.model;

import java.nio.file.Path;
import java.util.List;

/** Result of exporting a PDF to images: the image files written, in page order. */
public record PdfToImageResult(List<Path> images) {
    public PdfToImageResult {
        images = List.copyOf(images);
    }

    public int count() { return images.size(); }
}
