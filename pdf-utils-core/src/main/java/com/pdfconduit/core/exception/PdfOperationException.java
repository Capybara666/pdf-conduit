package com.pdfconduit.core.exception;

public class PdfOperationException extends Exception {
    public PdfOperationException(String message, Throwable cause) {
        super(message, cause);
    }
    public PdfOperationException(String message) {
        super(message);
    }
}
