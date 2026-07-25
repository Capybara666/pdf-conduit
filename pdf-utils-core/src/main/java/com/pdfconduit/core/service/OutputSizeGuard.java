package com.pdfconduit.core.service;

import com.pdfconduit.core.exception.PdfOperationException;

/**
 * A <em>running</em> ceiling on the output a multi-output, in-memory operation accumulates.
 *
 * <p>Operations that materialise a {@code List<byte[]>} (one part per page) hold every part in the
 * heap until the caller is done with them, so a pathological input (thousands of pages, a high
 * render DPI) can allocate gigabytes before the operation ever returns. Such operations call
 * {@link #check(long)} after each produced part with the encoded bytes accumulated <em>so far</em>,
 * letting the caller abort the run the moment its budget is blown — a clean
 * {@link PdfOperationException} early instead of an {@code OutOfMemoryError} at the end.
 *
 * <p>The same shape as {@link com.pdfconduit.core.operations.PdfCompressor.PageCountGuard}: an
 * optional ({@code null} ⇒ unbounded, the desktop/CLI default) callback the caller supplies, so
 * core stays free of any policy about how large "too large" is.
 */
@FunctionalInterface
public interface OutputSizeGuard {

    /**
     * @param accumulatedBytes summed size of the parts produced so far by this call
     * @throws PdfOperationException to abort the operation immediately
     */
    void check(long accumulatedBytes) throws PdfOperationException;
}
