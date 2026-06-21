package com.pdfconduit.core.model;

/**
 * Output raster format for {@link com.pdfconduit.core.operations.PdfToImageConverter}.
 * Pairs the ImageIO writer name with the file extension; JPEG is lossy (honours a
 * quality setting), PNG is lossless.
 */
public enum ImageFormat {
    PNG("png", "png"),
    JPEG("jpeg", "jpg");

    private final String imageioName;
    private final String extension;

    ImageFormat(String imageioName, String extension) {
        this.imageioName = imageioName;
        this.extension = extension;
    }

    /** ImageIO format name (the writer to look up). */
    public String imageioName() { return imageioName; }

    /** File extension (without the dot). */
    public String extension() { return extension; }

    /** True when the format is lossy and a JPEG quality applies. */
    public boolean isLossy() { return this == JPEG; }
}
