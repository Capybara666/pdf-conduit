package com.pdfconduit.web.error;

import com.pdfconduit.core.exception.BatchFatal;
import com.pdfconduit.core.exception.PdfOperationException;

/**
 * The request is legal but its <em>result</em> would be too big for one request to hold: the pages
 * it would rasterise, or the bytes its parts accumulate, exceed this server's per-request output
 * budget ({@code pdfconduit.web.render.max-total-output-pixels} /
 * {@code pdfconduit.web.processing.max-total-output-bytes}).
 *
 * <p>Distinct from {@link TooLargeException} (413, the <em>upload</em> is too big) and from a plain
 * operation failure: it maps to <b>422 {@code output_too_large}</b> so the frontend can tell the
 * user precisely what to change (fewer pages/files, lower DPI) instead of showing a generic
 * "operation failed". Extends {@link PdfOperationException} so it travels through the core
 * callbacks and the existing {@code throws} signatures unchanged, while its own handler in
 * {@link com.pdfconduit.web.error.GlobalExceptionHandler} (a more specific match) wins over the
 * generic {@code operation_failed} mapping.
 *
 * <p>{@link BatchFatal} because the ceiling is on the <em>request</em>, not on one of its files: a
 * partial-tolerant MAP batch ({@code MemoryOperations.mapPartial}) must fail outright rather than
 * return the files rendered so far and blame the one that happened to tip the budget over.
 */
public class OutputTooLargeException extends PdfOperationException implements BatchFatal {

    public OutputTooLargeException(String message) {
        super(message);
    }
}
