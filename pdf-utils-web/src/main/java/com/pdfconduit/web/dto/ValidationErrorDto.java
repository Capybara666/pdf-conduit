package com.pdfconduit.web.dto;

import com.pdfconduit.core.pipeline.ValidationError;

/** JSON view of a pipeline validation problem: the offending node id (nullable) and a message. */
public record ValidationErrorDto(String nodeId, String message) {

    public static ValidationErrorDto of(ValidationError e) {
        return new ValidationErrorDto(e.nodeId(), e.message());
    }
}
