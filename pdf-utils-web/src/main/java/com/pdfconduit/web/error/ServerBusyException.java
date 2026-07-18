package com.pdfconduit.web.error;

/**
 * Thrown when a heavy operation cannot be admitted right now — the concurrency permit could not
 * be acquired, or admitting the request would push in-flight bytes over the anti-OOM cap, or too
 * many LibreOffice conversions are already running. Mapped to HTTP 503 by
 * {@link GlobalExceptionHandler} (code {@code server_busy}).
 */
public class ServerBusyException extends RuntimeException {
    public ServerBusyException() {
        super("Server busy, try again shortly.");
    }

    public ServerBusyException(String message) {
        super(message);
    }
}
