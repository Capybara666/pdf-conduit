package com.pdfconduit.web.error;

/**
 * Thrown when a heavy operation exceeds the configured processing timeout and is cancelled.
 * Mapped to HTTP 503 by {@link GlobalExceptionHandler} (code {@code processing_timeout}). Note
 * that PDFBox work may not honour thread interruption immediately, so cancelling only guarantees
 * the client is shed; the concurrency cap bounds how much stuck work can accumulate.
 */
public class ProcessingTimeoutException extends RuntimeException {
    public ProcessingTimeoutException() {
        super("The operation took too long and was cancelled. Try a smaller file.");
    }
}
