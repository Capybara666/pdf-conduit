package com.pdfconduit.core.model;

import java.nio.file.Path;

/**
 * Crop every page of {@code input} by trimming a margin off each edge. The four margins
 * ({@code top}/{@code right}/{@code bottom}/{@code left}) are measured in points, or in
 * millimetres when {@code millimetres} is {@code true}. Cropping adjusts each page's crop
 * box (the visible region); the underlying content is preserved, so the crop is reversible.
 * A margin that would collapse a page to zero (or negative) size is clamped to a sliver.
 */
public record CropOptions(Path input, double top, double right, double bottom, double left,
                          boolean millimetres, Path output) {}
