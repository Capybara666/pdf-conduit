package com.pdfconduit.core.model;

/**
 * Where to stamp a signature image on one page, in <b>displayed-page points</b> with a
 * <b>top-left origin</b> (x grows right, y grows down) — the page exactly as the user
 * sees it in the viewer, rotation already applied. This is the same coordinate space
 * {@link RedactRegion} uses, so a placement box drawn in the web viewer maps directly.
 *
 * @param imageIndex which signature image (0-based) in the supplied image list to stamp
 * @param pageIndex  zero-based page the signature belongs to
 * @param x          left edge, points from the page's left
 * @param y          top edge, points from the page's top
 * @param width      drawn width in points
 * @param height     drawn height in points
 */
public record SignPlacement(int imageIndex, int pageIndex,
                            double x, double y, double width, double height) {}
