package com.pdfconduit.web.dto;

import com.pdfconduit.core.model.SignPlacement;

/**
 * JSON shape of a signature placement as posted by the client (the {@code placements}
 * param is an array of these). Coordinates are in displayed-page points, top-left origin;
 * {@code imageIndex} selects which uploaded signature image to stamp.
 */
public record SignPlacementDto(int imageIndex, int pageIndex,
                               double x, double y, double width, double height) {

    public SignPlacement toPlacement() {
        return new SignPlacement(imageIndex, pageIndex, x, y, width, height);
    }
}
