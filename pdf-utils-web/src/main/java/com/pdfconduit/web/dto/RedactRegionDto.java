package com.pdfconduit.web.dto;

import com.pdfconduit.core.model.RedactRegion;

/**
 * JSON shape of a redaction rectangle as posted by the client (the {@code regions}
 * param is an array of these). Coordinates are in displayed-page points, top-left origin.
 *
 * <p>A rectangle that cannot cover anything is rejected here, at the edge (→ 400
 * {@code bad_request}), rather than being quietly skipped further down: a redaction request the
 * server cannot honour must fail, never come back as a {@code *_redacted.pdf} with the data intact.
 * The page index is range-checked against the real document later, by the core redactor.
 */
public record RedactRegionDto(int pageIndex, double x, double y, double width, double height) {

    public RedactRegionDto {
        if (pageIndex < 0) {
            throw new IllegalArgumentException(
                "Redaction region has a negative pageIndex (" + pageIndex + "); pages are 0-based.");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException(
                "Redaction region on page " + (pageIndex + 1) + " has a non-finite position.");
        }
        if (!(width > 0) || !(height > 0) || !Double.isFinite(width) || !Double.isFinite(height)) {
            throw new IllegalArgumentException("Redaction region on page " + (pageIndex + 1)
                + " has no area (width=" + width + ", height=" + height + "); it would cover nothing.");
        }
    }

    public RedactRegion toRegion() {
        return new RedactRegion(pageIndex, x, y, width, height);
    }
}
