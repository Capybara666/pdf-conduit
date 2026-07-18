package com.pdfconduit.core.util;

import com.pdfconduit.core.exception.PdfOperationException;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;

/**
 * An {@link AutoCloseable} handle around a single, already-parsed {@link PDDocument} so a caller can
 * parse a PDF's bytes <em>once</em> and reuse that open document for several read-only checks
 * (page-count guard, render-size guard, page-range resolution) instead of re-parsing the same bytes
 * for each. The stateless web backend re-parses the same upload three or four times per request
 * today; opening one {@code LoadedPdf} at the top of a single-file operation collapses that to one
 * web-layer parse.
 *
 * <p>Unlike the {@code final}-with-private-constructor utility classes elsewhere in {@code core},
 * this is deliberately an <em>instance</em> holder: it owns a mutable, non-thread-safe
 * {@link PDDocument} that must be closed, so it carries per-instance state and a lifecycle. It is
 * <strong>not</strong> thread-safe — a {@code LoadedPdf} must be created, used and closed on a
 * single thread. Always use it in try-with-resources.
 *
 * <p>Opening goes through {@link PdfLoader#load(byte[])} so the user-facing "password-protected" /
 * "damaged" error messages stay identical to every other load path.
 */
public final class LoadedPdf implements AutoCloseable {

    private final PDDocument document;
    private final int pageCount;
    private boolean closed;

    private LoadedPdf(PDDocument document) {
        this.document = document;
        this.pageCount = document.getNumberOfPages();
    }

    /**
     * Parses {@code pdf} once via {@link PdfLoader#load(byte[])} (same clear messages for
     * protected / damaged input) and caches its page count. The caller owns the returned handle and
     * must {@link #close()} it (use try-with-resources).
     */
    public static LoadedPdf open(byte[] pdf) throws PdfOperationException {
        return new LoadedPdf(PdfLoader.load(pdf));
    }

    /** The open document — read-only use only; do not mutate or close it directly. */
    public PDDocument document() {
        return document;
    }

    /** Page count captured at construction (no re-parse). */
    public int pageCount() {
        return pageCount;
    }

    /** Serialises the open document to bytes (delegates to {@link PdfLoader#toBytes(PDDocument)}). */
    public byte[] toBytes() throws IOException {
        return PdfLoader.toBytes(document);
    }

    /** Closes the underlying document. Idempotent: safe to call (or double-call) via try-with-resources. */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        document.close();
    }
}
