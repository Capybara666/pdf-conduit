package com.pdfconduit.web.guard;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.operations.PdfOcr;
import com.pdfconduit.core.pipeline.PipelineGuard;
import com.pdfconduit.core.util.LoadedPdf;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.error.OcrDisabledException;
import com.pdfconduit.web.plan.PlanLimits;
import com.pdfconduit.web.plan.PlanLimitsResolver;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
 * <p>The ceilings are read from the resolved {@link PlanLimits} (today the constant FREE plan built
 * from {@code WebProperties}, so the values are identical to the ones {@code WebOperations} guards
 * with) and OCR availability from the system-level {@code pdfconduit.web.ocr.enabled} toggle plus
 * {@link PdfOcr#available()}, mirroring {@code /api/ocr} exactly — including the exception types, so
 * a rejection maps to the same status/code a client would get from the equivalent single operation
 * (excessive DPI → 400 {@code bad_request}, page bomb → 422 {@code operation_failed}, OCR off → 415
 * {@code ocr_disabled}, OCR saturated → 503 {@code server_busy}).
 */
@Component
public class PipelineLimitsGuard implements PipelineGuard {

    private final OcrGuard ocrGuard;
    private final int maxPages;
    private final int maxDpi;
    private final long maxOutputPixels;
    private final boolean ocrEnabled;

    public PipelineLimitsGuard(OcrGuard ocrGuard, PlanLimitsResolver planLimits, WebProperties props) {
        this.ocrGuard = ocrGuard;
        PlanLimits plan = planLimits.resolveDefault();
        this.maxPages = plan.maxPages();
        this.maxDpi = plan.maxDpi();
        this.maxOutputPixels = plan.maxOutputPixels();
        this.ocrEnabled = props.ocrEnabled();
    }

    /** PDF-bomb guard: reject a document whose page count exceeds the ceiling (→ 422). */
    @Override
    public void checkDocument(byte[] pdf) throws PdfOperationException {
        if (maxPages <= 0) return;
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            checkPageCount(lp.pageCount());
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }

    /**
     * The same ceiling on an already-known page count — how an amplifying node (ARRANGE, whose
     * order expression duplicates pages) is refused before it builds anything. Same exception type
     * as {@link #checkDocument}, so an expanded arrange answers exactly like a page-bomb upload
     * (→ 422), matching {@code /api/arrange}.
     */
    @Override
    public void checkPageCount(int pages) throws PdfOperationException {
        if (maxPages > 0 && pages > maxPages) {
            throw new PdfOperationException(
                "PDF exceeds the maximum page count (" + maxPages + ").");
        }
    }

    /**
     * Raster-render guard: reject a DPI above the configured ceiling (→ 400) and any page whose
     * rendered pixel area would exceed {@code maxOutputPixels} (→ 422), BEFORE a page is rasterised.
     */
    @Override
    public void checkRender(byte[] pdf, int dpi) throws PdfOperationException {
        if (maxDpi > 0 && dpi > maxDpi) {
            throw new IllegalArgumentException(
                "Requested DPI " + dpi + " exceeds the maximum allowed (" + maxDpi + ").");
        }
        if (maxOutputPixels <= 0) return;
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            for (PDPage page : lp.document().getPages()) {
                PDRectangle box = page.getCropBox();
                double widthPx = box.getWidth() / 72.0 * dpi;
                double heightPx = box.getHeight() / 72.0 * dpi;
                if (widthPx * heightPx > maxOutputPixels) {
                    throw new PdfOperationException(
                        "Rendering this document at " + dpi + " DPI would exceed the output-size "
                        + "limit; choose a lower DPI.");
                }
            }
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
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
