package com.pdfconduit.web.guard;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.util.LoadedPdf;
import com.pdfconduit.web.plan.PlanLimits;
import com.pdfconduit.web.plan.RequestPlan;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.function.IntPredicate;

/**
 * The two ceilings that depend on what is <em>inside</em> a document — its page count and what it
 * costs to rasterise — enforced in one place for every surface that opens a PDF.
 *
 * <p>{@code WebOperations} (the single-operation endpoints) and {@link PipelineLimitsGuard} (the
 * client-supplied graph) must reject exactly the same documents with exactly the same messages, or
 * the pipeline becomes a way to reach the same core operations under different rules. Sharing one
 * implementation is what makes that true by construction rather than by two copies staying in step.
 *
 * <p>Ceilings come from the {@link PlanLimits} resolved for the CURRENT request via
 * {@link RequestPlan} — never snapshotted here, or a per-caller plan could never move them.
 */
@Component
public class DocumentLimits {

    private final RequestPlan requestPlan;

    public DocumentLimits(RequestPlan requestPlan) {
        this.requestPlan = requestPlan;
    }

    /** This request's PDF-bomb page ceiling ({@code <= 0} ⇒ no ceiling). */
    public int maxPages() {
        return requestPlan.current().maxPages();
    }

    /** This request's raster-render DPI ceiling ({@code <= 0} ⇒ no ceiling). */
    public int maxDpi() {
        return requestPlan.current().maxDpi();
    }

    /**
     * PDF-bomb guard on an already-known page count (→ 422). Also serves as the page-count callback
     * the compressor takes, so its lossless-pass parse doubles as the guard parse, and as the check
     * an amplifying operation (arrange, merge) applies to the count it is <em>about</em> to build.
     */
    public void checkPageCount(int pageCount) throws PdfOperationException {
        int maxPages = maxPages();
        if (maxPages > 0 && pageCount > maxPages) {
            throw new PdfOperationException("PDF exceeds the maximum page count (" + maxPages + ").");
        }
    }

    /** As {@link #checkPageCount(int)} off an already-open handle — no re-parse. */
    public void checkPageCount(LoadedPdf lp) throws PdfOperationException {
        checkPageCount(lp.pageCount());
    }

    /** As {@link #checkPageCount(int)} for bytes that still have to be parsed. */
    public void checkDocument(byte[] pdf) throws PdfOperationException {
        if (maxPages() <= 0) return;
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            checkPageCount(lp.pageCount());
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Raster-render guard for a document whose every page will be rasterised. See
     * {@link #checkRender(LoadedPdf, int, IntPredicate)}.
     */
    public long checkRender(LoadedPdf lp, int dpi) throws PdfOperationException {
        return checkRender(lp, dpi, null);
    }

    /**
     * Raster-render guard: reject a DPI above the ceiling (→ 400) and any page whose rendered pixel
     * area would exceed the per-page ceiling (→ 422), BEFORE any page is rasterised. The per-page
     * check covers <em>every</em> page of the document.
     *
     * <p>Per-page ceilings alone do not bound {@code pages × files}: the summed area of the pages
     * this call will actually render is returned so the caller can accumulate it into the
     * per-request {@link OutputBudget}.
     *
     * @param rendered 0-based page indices that will really be rasterised; {@code null} ⇒ all pages
     * @return summed pixel area of the pages that will be rendered
     */
    public long checkRender(LoadedPdf lp, int dpi, IntPredicate rendered)
            throws PdfOperationException {
        // One resolve for the whole check: this request's DPI and per-page pixel ceilings.
        PlanLimits plan = requestPlan.current();
        int maxDpi = plan.maxDpi();
        long maxOutputPixels = plan.maxOutputPixels();
        if (maxDpi > 0 && dpi > maxDpi) {
            throw new IllegalArgumentException(
                "Requested DPI " + dpi + " exceeds the maximum allowed (" + maxDpi + ").");
        }
        long total = 0;
        int index = 0;
        for (PDPage page : lp.document().getPages()) {
            PDRectangle box = page.getCropBox();
            double widthPx = box.getWidth() / 72.0 * dpi;
            double heightPx = box.getHeight() / 72.0 * dpi;
            double area = widthPx * heightPx;
            if (maxOutputPixels > 0 && area > maxOutputPixels) {
                throw new PdfOperationException(
                    "Rendering this document at " + dpi + " DPI would exceed the output-size "
                    + "limit; choose a lower DPI.");
            }
            if (rendered == null || rendered.test(index)) total += (long) area;
            index++;
        }
        return total;
    }

    /** As {@link #checkRender(LoadedPdf, int)} for bytes that still have to be parsed. */
    public long checkRender(byte[] pdf, int dpi) throws PdfOperationException {
        PlanLimits plan = requestPlan.current();
        if (plan.maxDpi() > 0 && dpi > plan.maxDpi()) {
            throw new IllegalArgumentException(
                "Requested DPI " + dpi + " exceeds the maximum allowed (" + plan.maxDpi() + ").");
        }
        if (plan.maxOutputPixels() <= 0) return 0;
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            return checkRender(lp, dpi, null);
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }
}
