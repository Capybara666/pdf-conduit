package com.pdfconduit.core.pipeline;

import com.pdfconduit.core.exception.PdfOperationException;

/**
 * Host-supplied ceilings applied <em>inside</em> an in-memory pipeline run
 * ({@link PipelineExecutor#runInMemory}).
 *
 * <p>A pipeline is a client-supplied graph: its nodes carry client-supplied render DPIs, OCR
 * settings and page expressions, and the executor calls the same heavy core operations the
 * single-operation surfaces expose. Those surfaces (the web backend's {@code /api/to-images},
 * {@code /api/ocr}, {@code /api/render}) enforce per-request ceilings — page count, render DPI,
 * rendered pixel area, whether OCR is available at all — before they call core. Without this hook
 * the pipeline endpoint would be a way to reach the very same operations with none of them applied.
 *
 * <p>The interface is deliberately tiny and fully defaulted, so:
 * <ul>
 *   <li>the desktop GUI/CLI (which run trusted, local pipelines) keep calling {@code runInMemory}
 *       unchanged and get {@link #NONE} — every check a no-op, zero extra PDF parses;</li>
 *   <li>the web layer implements it over its plan limits / guards and passes it in;</li>
 *   <li>core stays JavaFX-free and headlessly testable (a test guard is a lambda-sized class).</li>
 * </ul>
 *
 * <p><b>Exception contract:</b> a {@link PdfOperationException} thrown by a check is wrapped by the
 * executor into a {@link PipelineException} like any other operation failure; a
 * <em>runtime</em> exception thrown by a check propagates out of {@code runInMemory} unchanged, so a
 * host can throw its own typed rejection (e.g. "OCR disabled" → HTTP 415) and have it mapped exactly
 * as the equivalent single-operation endpoint would map it.
 */
public interface PipelineGuard {

    /** The permissive default used by desktop/CLI and headless tests: every check passes. */
    PipelineGuard NONE = new PipelineGuard() {};

    /** A unit of OCR work, so a host can run it under its own concurrency + timeout gate. */
    @FunctionalInterface
    interface OcrWork {
        byte[] run() throws PdfOperationException;
    }

    /**
     * PDF-bomb ceiling. Called with the bytes of every document entering the pipeline and of every
     * document a node produced by <em>amplification</em> (merge, unlock) — i.e. wherever the page
     * count is not already bounded by an input that was checked.
     */
    default void checkDocument(byte[] pdf) throws PdfOperationException {}

    /**
     * Raster-render ceiling. Called before any node rasterises pages, with the document about to be
     * rendered and the effective DPI the node asked for (to-images, OCR, GDPR redact). Implementors
     * reject both an excessive DPI and a page whose rendered pixel area would be too large.
     */
    default void checkRender(byte[] pdf, int dpi) throws PdfOperationException {}

    /** Availability gate for OCR nodes; throw to reject the run (e.g. OCR disabled by config). */
    default void checkOcrAllowed() throws PdfOperationException {}

    /** Runs an OCR node's work; hosts wrap it in their own concurrency/timeout gate. */
    default byte[] runOcr(OcrWork work) throws PdfOperationException {
        return work.run();
    }
}
