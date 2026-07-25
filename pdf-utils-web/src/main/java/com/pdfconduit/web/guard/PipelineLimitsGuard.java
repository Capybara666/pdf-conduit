package com.pdfconduit.web.guard;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.operations.PdfOcr;
import com.pdfconduit.core.pipeline.PipelineGuard;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.error.OcrDisabledException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * The web layer's {@link PipelineGuard}: it applies to a client-submitted pipeline the very same
 * per-request ceilings the single-operation endpoints apply — the PDF-bomb page cap, the render
 * DPI + output-pixel cap, the OCR availability gate and {@link OcrGuard}'s concurrency/timeout
 * permit. Without it {@code POST /api/pipeline/run} would reach the identical core operations with
 * none of them enforced: a {@code TO_IMAGES} node could ask for 1200 DPI (hundreds of megabytes per
 * page), an OCR node could spawn {@code tesseract} even with OCR switched off, and a page-bomb PDF
 * could walk straight through.
 *
 * <p>The document ceilings are not re-implemented here: they are {@link DocumentLimits}, the same
 * object {@code WebOperations} guards with, so a pipeline node and its single-operation twin refuse
 * the same document with the same message by construction. OCR availability comes from the
 * system-level {@code pdfconduit.web.ocr.enabled} toggle plus {@link PdfOcr#available()}, mirroring
 * {@code /api/ocr} exactly — including the exception types, so a rejection maps to the same
 * status/code a client would get from the equivalent single operation (excessive DPI → 400
 * {@code bad_request}, page bomb → 422 {@code operation_failed}, OCR off → 415
 * {@code ocr_disabled}, OCR saturated → 503 {@code server_busy}).
 */
@Component
public class PipelineLimitsGuard implements PipelineGuard {

    private final OcrGuard ocrGuard;
    private final DocumentLimits limits;
    private final boolean ocrEnabled;

    public PipelineLimitsGuard(OcrGuard ocrGuard, DocumentLimits limits, WebProperties props) {
        this.ocrGuard = ocrGuard;
        this.limits = limits;
        this.ocrEnabled = props.ocrEnabled();
    }

    /** PDF-bomb guard: reject a document whose page count exceeds the ceiling (→ 422). */
    @Override
    public void checkDocument(byte[] pdf) throws PdfOperationException {
        limits.checkDocument(pdf);
    }

    /**
     * The same ceiling on an already-known page count — how an amplifying node (ARRANGE, whose
     * order expression duplicates pages) is refused before it builds anything. Same exception type
     * as {@link #checkDocument}, so an expanded arrange answers exactly like a page-bomb upload
     * (→ 422), matching {@code /api/arrange}.
     */
    @Override
    public void checkPageCount(int pages) throws PdfOperationException {
        limits.checkPageCount(pages);
    }

    /**
     * Raster-render guard: reject a DPI above the configured ceiling (→ 400) and any page whose
     * rendered pixel area would exceed {@code maxOutputPixels} (→ 422), BEFORE a page is rasterised.
     */
    @Override
    public void checkRender(byte[] pdf, int dpi) throws PdfOperationException {
        limits.checkRender(pdf, dpi);
    }

    /** Same gate as {@code /api/ocr}: OCR off by config or no Tesseract ⇒ 415 {@code ocr_disabled}. */
    @Override
    public void checkOcrAllowed() {
        if (!ocrEnabled || !PdfOcr.available()) {
            throw new OcrDisabledException();
        }
    }

    /** Runs the node's OCR under the shared OCR permit + timeout, exactly like {@code /api/ocr}. */
    @Override
    public byte[] runOcr(OcrWork work) throws PdfOperationException {
        try {
            return ocrGuard.run(work::run);
        } catch (IOException e) {
            throw new PdfOperationException("OCR failed: " + e.getMessage(), e);
        }
    }
}
