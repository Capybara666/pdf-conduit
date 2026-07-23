package com.pdfconduit.core.model;

import java.nio.file.Path;
import java.util.List;

/**
 * Options for the image → PDF conversion.
 *
 * @param images    source image files, one per page
 * @param pageSize  page sizing strategy
 * @param output    destination PDF path
 * @param grayscale when {@code true} every source image is converted to grayscale (regardless of
 *                  its colorspace — RGB, indexed, CMYK, or with alpha) before being placed
 */
public record ImageToPdfOptions(List<Path> images, PageSize pageSize, Path output, boolean grayscale) {
    public ImageToPdfOptions {
        images = List.copyOf(images);
    }

    /** Convenience constructor: colour images (no grayscale conversion). */
    public ImageToPdfOptions(List<Path> images, PageSize pageSize, Path output) {
        this(images, pageSize, output, false);
    }
}
