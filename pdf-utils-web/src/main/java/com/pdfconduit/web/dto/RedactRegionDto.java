package com.pdfconduit.web.dto;

import com.pdfconduit.core.model.RedactRegion;

/**
 * JSON shape of a redaction rectangle as posted by the client (the {@code regions}
 * param is an array of these). Coordinates are in displayed-page points, top-left origin.
 */
public record RedactRegionDto(int pageIndex, double x, double y, double width, double height) {

    public RedactRegion toRegion() {
        return new RedactRegion(pageIndex, x, y, width, height);
    }
}
