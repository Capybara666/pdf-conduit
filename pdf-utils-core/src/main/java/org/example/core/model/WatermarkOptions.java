package org.example.core.model;

import java.nio.file.Path;

/**
 * Stamp a watermark onto every page of {@code input}. Exactly one of {@code text}
 * or {@code image} must be set: text draws a string, image stamps a picture/logo
 * (PNG transparency is preserved). {@code opacity} is 0–1 and {@code rotationDegrees}
 * rotates the mark around each page's centre (45° gives a diagonal stamp).
 * {@code scale} is the target width as a fraction of the page width (e.g. 0.7 ≈ 70%),
 * capped so it still fits the page height.
 */
public record WatermarkOptions(Path input, String text, Path image,
                               double opacity, double rotationDegrees, double scale, Path output) {}
