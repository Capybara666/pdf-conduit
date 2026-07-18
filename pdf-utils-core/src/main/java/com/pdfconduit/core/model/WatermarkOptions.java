package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Stamp a watermark onto every page of {@code input}. Exactly one of {@code text}
 * or {@code image} must be set: text draws a string, image stamps a picture/logo
 * (PNG transparency is preserved). {@code opacity} is 0–1 and {@code rotationDegrees}
 * rotates the mark (45° gives a diagonal stamp). {@code scale} is the target width as
 * a fraction of the page width (e.g. 0.7 ≈ 70%), capped so it still fits the page height.
 *
 * <p>{@code layout} chooses how the mark repeats: {@link Layout#SINGLE} stamps once (at
 * {@code position}), {@link Layout#TILE} repeats it across the whole page in a grid, and
 * {@link Layout#DIAGONAL} repeats it along a diagonal band. {@code position} only applies
 * to {@code SINGLE}. {@code color} is a {@code #RRGGBB} hex string for the <em>text</em>
 * watermark (ignored for images); {@code null}/blank keeps the default grey.
 */
public record WatermarkOptions(Path input, String text, Path image,
                               double opacity, double rotationDegrees, double scale,
                               Layout layout, Position position, String color, Path output) {

    /** How the watermark repeats across each page. */
    public enum Layout { SINGLE, TILE, DIAGONAL }

    /** Anchor for a {@link Layout#SINGLE} watermark. */
    public enum Position { CENTER, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /**
     * Backwards-compatible constructor: a single, centred, default-grey watermark
     * (the historical behaviour before layout/position/tint were added).
     */
    public WatermarkOptions(Path input, String text, Path image,
                            double opacity, double rotationDegrees, double scale, Path output) {
        this(input, text, image, opacity, rotationDegrees, scale,
            Layout.SINGLE, Position.CENTER, null, output);
    }
}
