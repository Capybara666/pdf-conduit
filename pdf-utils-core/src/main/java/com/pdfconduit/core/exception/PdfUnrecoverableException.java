package com.pdfconduit.core.exception;

/**
 * A damaged file that could not be recovered at all: not enough PDF structure survived for even
 * the lenient, brute-force parser to rebuild a document with readable pages.
 *
 * <p>Deliberately distinct from a plain {@link PdfOperationException} so a surface can be honest
 * about <em>why</em> a repair failed — "this file cannot be recovered" is a different, terminal
 * answer from "wrong password" or "unsupported input". The web layer maps it to its own
 * {@code repair_failed} error code.
 */
public class PdfUnrecoverableException extends PdfOperationException {

    public PdfUnrecoverableException(String message) {
        super(message);
    }

    public PdfUnrecoverableException(String message, Throwable cause) {
        super(message, cause);
    }
}
