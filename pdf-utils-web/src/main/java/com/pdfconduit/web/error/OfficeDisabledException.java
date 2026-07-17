package com.pdfconduit.web.error;

/**
 * Thrown when an office/document upload (`.docx`, `.xlsx`, …) arrives but office conversion is
 * disabled ({@code pdfconduit.web.office.enabled=false}). Mapped to HTTP 415 by
 * {@link GlobalExceptionHandler}.
 */
public class OfficeDisabledException extends RuntimeException {
    public OfficeDisabledException(String filename) {
        super("Office document conversion is disabled on this server; cannot process \""
            + filename + "\". Upload a PDF or image instead.");
    }
}
