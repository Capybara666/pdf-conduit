package com.pdfconduit.core.model;

/**
 * A rectangle to redact on one page, in <b>displayed-page points</b> with a
 * <b>top-left origin</b> (x grows right, y grows down) — i.e. the page as the
 * user sees it in the viewer, rotation already applied. This matches the
 * coordinate space a UI naturally produces from mouse positions; the redactor
 * scales it to render pixels.
 *
 * @param pageIndex zero-based page the rectangle belongs to
 * @param x         left edge, points from the page's left
 * @param y         top edge, points from the page's top
 * @param width     rectangle width in points
 * @param height    rectangle height in points
 */
public record RedactRegion(int pageIndex, double x, double y, double width, double height) {}
