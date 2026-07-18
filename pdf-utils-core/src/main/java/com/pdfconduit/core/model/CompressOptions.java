package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Options for {@link com.pdfconduit.core.operations.PdfCompressor}.
 *
 * @param input          the source file (any supported input, routed to PDF by callers)
 * @param targetSizeBytes the size the compressor walks its ladder toward
 * @param output         where the compressed PDF is written
 * @param targetDpi      an optional resolution ceiling for embedded images: when not
 *                       {@link DpiPreset#NONE}, images are downscaled so their effective
 *                       resolution never exceeds the preset, on top of the size-driven ladder
 * @param grayscale      when {@code true}, images are re-encoded as grayscale for extra savings
 */
public record CompressOptions(Path input, long targetSizeBytes, Path output,
                              DpiPreset targetDpi, boolean grayscale) {

    /** A resolution ceiling for embedded images. {@link #NONE} keeps the adaptive default behaviour. */
    public enum DpiPreset {
        /** No DPI ceiling — the size-driven ladder decides downscaling (default). */
        NONE(0),
        /** ~72 DPI — screen viewing; smallest output. */
        SCREEN(72),
        /** ~150 DPI — e-book / on-screen reading. */
        EBOOK(150),
        /** ~300 DPI — print quality. */
        PRINT(300);

        private final int dpi;

        DpiPreset(int dpi) {
            this.dpi = dpi;
        }

        /** The target resolution in dots-per-inch (0 for {@link #NONE}). */
        public int dpi() {
            return dpi;
        }
    }

    /** Normalises a null {@code targetDpi} to {@link DpiPreset#NONE}. */
    public CompressOptions {
        if (targetDpi == null) targetDpi = DpiPreset.NONE;
    }

    /** Backward-compatible constructor: no DPI ceiling, no grayscale (the pre-existing behaviour). */
    public CompressOptions(Path input, long targetSizeBytes, Path output) {
        this(input, targetSizeBytes, output, DpiPreset.NONE, false);
    }
}
