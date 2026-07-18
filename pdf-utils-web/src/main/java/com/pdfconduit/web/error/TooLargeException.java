package com.pdfconduit.web.error;

/**
 * Thrown when an upload exceeds a free-tier limit (a single file over the free per-file cap, or a
 * request with more files than the free per-request cap). Mapped to HTTP 413 by
 * {@link GlobalExceptionHandler} (code {@code too_large}). Distinct from the absolute multipart
 * ceiling, which surfaces as {@code file_too_large}.
 */
public class TooLargeException extends RuntimeException {
    public TooLargeException(String message) {
        super(message);
    }
}
