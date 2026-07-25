package com.pdfconduit.web.service;

import com.pdfconduit.core.analyze.PiiCategory;
import com.pdfconduit.core.analyze.PiiFinding;
import com.pdfconduit.core.analyze.PiiRegion;
import com.pdfconduit.core.analyze.PiiScanResult;
import com.pdfconduit.core.analyze.PiiScanner;
import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.CompressBytesResult;
import com.pdfconduit.core.model.CompressOptions;
import com.pdfconduit.core.model.ImageFormat;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.PdfMetadata;
import com.pdfconduit.core.model.RedactBytesResult;
import com.pdfconduit.core.model.RedactRegion;
import com.pdfconduit.core.model.RepairBytesResult;
import com.pdfconduit.core.model.SignPlacement;
import com.pdfconduit.core.model.TextFormat;
import com.pdfconduit.core.operations.PdfArranger;
import com.pdfconduit.core.operations.PdfCompressor;
import com.pdfconduit.core.operations.PdfCropper;
import com.pdfconduit.core.operations.PdfMerger;
import com.pdfconduit.core.operations.PdfMetadataEditor;
import com.pdfconduit.core.operations.PdfPageMarker;
import com.pdfconduit.core.operations.PdfOcr;
import com.pdfconduit.core.operations.PdfProtector;
import com.pdfconduit.core.operations.PdfRedactor;
import com.pdfconduit.core.operations.PdfRepairer;
import com.pdfconduit.core.operations.PdfRotator;
import com.pdfconduit.core.operations.PdfSigner;
import com.pdfconduit.core.operations.PdfSplitter;
import com.pdfconduit.core.operations.PdfTextExporter;
import com.pdfconduit.core.operations.PdfToImageConverter;
import com.pdfconduit.core.operations.PdfUnlocker;
import com.pdfconduit.core.operations.PdfWatermarker;
import com.pdfconduit.core.service.MemoryOperations;
import com.pdfconduit.core.service.NamedBytes;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.core.util.FileTypeDetector;
import com.pdfconduit.core.util.LoadedPdf;
import com.pdfconduit.core.util.PageOrderParser;
import com.pdfconduit.core.util.PageRangeParser;
import com.pdfconduit.core.util.PdfLoader;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.error.OcrDisabledException;
import com.pdfconduit.web.error.OutputTooLargeException;
import com.pdfconduit.web.guard.OcrGuard;
import com.pdfconduit.web.guard.OfficeGuard;
import com.pdfconduit.web.guard.OutputBudget;
import com.pdfconduit.web.plan.PlanLimits;
import com.pdfconduit.web.plan.RequestPlan;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * The bridge from HTTP to {@code pdf-utils-core}, entirely in memory: one method per operation
 * taking {@link NamedBytes} uploads (filename + bytes) and returning result {@link NamedBytes}
 * (or {@code byte[]}). Non-PDF inputs are routed to PDF bytes via the shared core
 * {@link MemoryOperations#toPdfBytes} (PDF passthrough, image → in-memory Image-to-PDF, office →
 * the documented temp-dir exception). Output names come from {@link OperationType#suffix()}.
 *
 * <p>Page-range parsing happens on the routed PDF <em>before</em> the operation runs, so a bad
 * range surfaces as {@link InvalidPageRangeException} (→ 400) rather than being wrapped as an
 * operation failure (→ 422).
 */
@Service
public class WebOperations {

    private final OfficeGuard officeGuard;
    private final OcrGuard ocrGuard;
    private final OutputBudget outputBudget;
    private final RequestPlan requestPlan;
    private final boolean ocrEnabled;
    private final String ocrLanguages;

    public WebOperations(OfficeGuard officeGuard, OcrGuard ocrGuard, OutputBudget outputBudget,
                         RequestPlan requestPlan, WebProperties props) {
        this.officeGuard = officeGuard;
        this.ocrGuard = ocrGuard;
        this.outputBudget = outputBudget;
        // Page-count and render ceilings are read from the plan resolved FOR THE CURRENT REQUEST
        // (today the constant FREE plan built from WebProperties, so identical values) — never
        // snapshotted here, or a per-caller paid plan could never move them. Office availability
        // stays a system-level WebProperties toggle.
        this.requestPlan = requestPlan;
        this.ocrEnabled = props.ocrEnabled();
        this.ocrLanguages = props.ocr().languages();
    }

    /** This request's PDF-bomb page ceiling ({@code <= 0} ⇒ no ceiling). */
    private int maxPages() {
        return requestPlan.current().maxPages();
    }

    /** This request's raster-render DPI ceiling ({@code <= 0} ⇒ no ceiling). */
    private int maxDpi() {
        return requestPlan.current().maxDpi();
    }

    // ------------------------------------------------------------------ MERGE

    /** Merge many inputs (pdf/image/office) into one PDF. */
    public NamedBytes merge(List<NamedBytes> inputs) throws PdfOperationException {
        // Convert + guard each input up front (office gated, page-count capped), then merge PDFs.
        return MemoryOperations.runReduce(OperationType.MERGE, pdfData(inputs), names(inputs),
            PdfMerger::executeBytes);
    }

    // ---------------------------------------------------------------- EXTRACT

    /** Extract {@code pagesExpr} (blank ⇒ all) combined into one PDF. */
    public NamedBytes extractCombine(NamedBytes in, String pagesExpr)
            throws PdfOperationException, InvalidPageRangeException {
        byte[] pdf = routeToPdf(in);
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            byte[] out = PdfSplitter.combineBytes(pdf, range(pagesExpr, lp));
            return new NamedBytes(MemoryOperations.outputName(OperationType.EXTRACT, in.filename()), out);
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }

    /** Extract {@code pagesExpr} (blank ⇒ all) as one PDF per page. */
    public List<NamedBytes> extractSeparate(NamedBytes in, String pagesExpr)
            throws PdfOperationException, InvalidPageRangeException {
        return extractSeparate(in, pagesExpr, 1);
    }

    /**
     * Extract {@code pagesExpr} (blank ⇒ all) as one PDF per group of {@code pagesPerChunk}
     * selected pages — the "split every N pages" mode. Chunking happens <em>within</em> the
     * selection, so the range narrows what is split and N only decides how it is cut up; the last
     * part may be shorter and an N at or above the selection size yields one part.
     */
    public List<NamedBytes> extractSeparate(NamedBytes in, String pagesExpr, int pagesPerChunk)
            throws PdfOperationException, InvalidPageRangeException {
        return extractSeparate(in, pagesExpr, pagesPerChunk, outputBudget.tally());
    }

    /**
     * As {@link #extractSeparate(NamedBytes, String, int)} but sharing one request-wide byte budget,
     * so a batch is bounded by what the whole response will carry, not by each file in isolation.
     *
     * <p>Public because a partial-tolerant batch runs one file per call (see
     * {@code MemoryOperations.mapPartial}): the controller owns the request's single
     * {@link #newOutputTally() tally} and hands the same one to every file, which is the only thing
     * that keeps the ceiling per-request rather than per-file.
     */
    public List<NamedBytes> extractSeparate(NamedBytes in, String pagesExpr, int pagesPerChunk,
                                            OutputBudget.Tally tally)
            throws PdfOperationException, InvalidPageRangeException {
        byte[] pdf = routeToPdf(in);
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            // Split is multi-output: every part is its own PDF held in the heap, so the run aborts
            // (422) the moment the request's accumulated result outgrows the budget.
            List<byte[]> pages = PdfSplitter.separateBytes(pdf, range(pagesExpr, lp), pagesPerChunk,
                tally.guard());
            tally.commit(pages);
            return nameMulti(OperationType.EXTRACT, in.filename(), pages, "pdf");
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Batch combine-extract: apply {@code pagesExpr} to every input, one combined PDF per input,
     * order preserved. Each output is named from its source file, so distinct sources stay distinct.
     */
    public List<NamedBytes> extractCombine(List<NamedBytes> inputs, String pagesExpr)
            throws PdfOperationException, InvalidPageRangeException {
        List<NamedBytes> out = new ArrayList<>(inputs.size());
        for (NamedBytes in : inputs) out.add(extractCombine(in, pagesExpr));
        return out;
    }

    /**
     * Batch separate-extract: apply {@code pagesExpr} to every input, one PDF per selected page,
     * all inputs' pages concatenated (order preserved). Per-page names carry each source's filename
     * ({@link #nameMulti}); any residual collision is de-duplicated when zipped.
     */
    public List<NamedBytes> extractSeparate(List<NamedBytes> inputs, String pagesExpr)
            throws PdfOperationException, InvalidPageRangeException {
        return extractSeparate(inputs, pagesExpr, 1);
    }

    /**
     * Batch "split every N pages": apply {@code pagesExpr} to every input and cut each one's
     * selection into parts of {@code pagesPerChunk} pages, all inputs' parts concatenated
     * (order preserved). Parts are chunked per input — a part never spans two source files.
     */
    public List<NamedBytes> extractSeparate(List<NamedBytes> inputs, String pagesExpr,
                                            int pagesPerChunk)
            throws PdfOperationException, InvalidPageRangeException {
        // One tally for the whole request: the ceiling is on everything the response will carry,
        // not on each file in isolation.
        OutputBudget.Tally tally = outputBudget.tally();
        List<NamedBytes> out = new ArrayList<>();
        for (NamedBytes in : inputs) out.addAll(extractSeparate(in, pagesExpr, pagesPerChunk, tally));
        return out;
    }

    // --------------------------------------------------------------- COMPRESS

    /**
     * Compress a single input to (at most) {@code targetBytes}; full metrics returned. When
     * {@code targetDpi} is not {@link CompressOptions.DpiPreset#NONE} images are additionally capped
     * to that resolution, and {@code grayscale} re-encodes images in grayscale for extra savings.
     */
    public CompressBytesResult compress(NamedBytes in, long targetBytes,
                                        CompressOptions.DpiPreset targetDpi, boolean grayscale)
            throws PdfOperationException {
        // Single-parse: skip the separate page-count-guard parse and fold the guard into the
        // document the compressor's lossless pass already opens (see PdfCompressor.PageCountGuard).
        return PdfCompressor.compressBytes(routeToPdf(in), targetBytes, targetDpi, grayscale,
            this::guardPageCountValue);
    }

    /** Batch-compress every input to (at most) {@code targetBytes}, with the same DPI/grayscale opts. */
    public List<NamedBytes> compressBatch(List<NamedBytes> inputs, long targetBytes,
                                          CompressOptions.DpiPreset targetDpi, boolean grayscale)
            throws PdfOperationException {
        return MemoryOperations.runBatch(OperationType.COMPRESS, pdfData(inputs), names(inputs),
            pdf -> PdfCompressor.compressBytes(pdf, targetBytes, targetDpi, grayscale, null).bytes());
    }

    // ----------------------------------------------------------------- ROTATE

    /** Batch-rotate every input by {@code angle} over {@code pagesExpr} (blank ⇒ all). */
    public List<NamedBytes> rotate(List<NamedBytes> inputs, String pagesExpr, int angle)
            throws PdfOperationException, InvalidPageRangeException {
        List<NamedBytes> out = new ArrayList<>(inputs.size());
        for (NamedBytes in : inputs) {
            byte[] pdf = toPdf(in);
            byte[] r = PdfRotator.executeBytes(pdf, range(pagesExpr, pdf), angle);
            out.add(new NamedBytes(MemoryOperations.outputName(OperationType.ROTATE, in.filename()), r));
        }
        return out;
    }

    // ---------------------------------------------------------------- ARRANGE

    /**
     * Reorder a single input's pages per {@code order} (e.g. {@code 3,1,2}).
     *
     * <p>Arrange is a page-count <em>amplifier</em>: repeats in the order expression duplicate
     * pages, so {@code 1,1,1,…} expands a one-page upload into a document of any size the
     * expression names — an in-memory page bomb built from a request that passes every input-side
     * limit. So the ceiling is checked on the expanded order's length (which <em>is</em> the result's
     * page count) as well as on the input, and before the document is built, so nothing large is
     * ever materialised.
     */
    public NamedBytes arrange(NamedBytes in, String order)
            throws PdfOperationException, InvalidPageRangeException {
        byte[] pdf = routeToPdf(in);
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            List<Integer> pageOrder = PageOrderParser.parse(order, lp.pageCount());
            guardPageCountValue(pageOrder.size());
            byte[] out = PdfArranger.executeBytes(pdf, pageOrder);
            return new NamedBytes(MemoryOperations.outputName(OperationType.ARRANGE, in.filename()), out);
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ TO-PDF

    /** Convert each input to its own PDF at {@code imageSize} (images honour the page size). */
    public List<NamedBytes> toPdf(List<NamedBytes> inputs, PageSize imageSize)
            throws PdfOperationException {
        List<NamedBytes> out = new ArrayList<>(inputs.size());
        for (NamedBytes in : inputs) {
            byte[] pdf;
            try {
                pdf = officeGuard.run(in.filename(),
                    () -> DocumentConverter.ensurePdfBytes(in.data(), in.filename(), imageSize));
            } catch (IOException e) {
                throw new PdfOperationException("Cannot convert input: " + e.getMessage(), e);
            }
            guardPageCount(pdf);
            out.add(new NamedBytes(
                MemoryOperations.outputName(OperationType.IMAGES_TO_PDF, in.filename()), pdf));
        }
        return out;
    }

    // ----------------------------------------------------------------- PROTECT

    public List<NamedBytes> protect(List<NamedBytes> inputs, String userPassword, String ownerPassword,
                                    int keyLength) throws PdfOperationException {
        return MemoryOperations.runBatch(OperationType.PROTECT, pdfData(inputs), names(inputs),
            pdf -> PdfProtector.executeBytes(pdf, userPassword, ownerPassword, keyLength));
    }

    // ------------------------------------------------------------------ UNLOCK

    public List<NamedBytes> unlock(List<NamedBytes> inputs, String password)
            throws PdfOperationException {
        // Unlock operates on the raw (still-encrypted) upload, so the page-count guard can only be
        // applied AFTER decryption — enforce it on the unlocked result to keep the PDF-bomb ceiling.
        return MemoryOperations.runBatch(OperationType.UNLOCK, data(inputs), names(inputs),
            pdf -> {
                byte[] out = PdfUnlocker.executeBytes(pdf, password);
                guardPageCount(out);
                return out;
            });
    }

    // ---------------------------------------------------------------- METADATA

    /** Read a PDF's document-info metadata (input may be office/image → converted first). */
    public PdfMetadata readMetadata(NamedBytes in) throws PdfOperationException {
        byte[] pdf = routeToPdf(in);
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            return PdfMetadataEditor.readBytes(pdf);
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }

    /** Edit (or strip) a PDF's metadata; null field = unchanged, empty = cleared. */
    public NamedBytes editMetadata(NamedBytes in, String title, String author, String subject,
                                   String keywords, boolean strip) throws PdfOperationException {
        byte[] pdf = routeToPdf(in);
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            byte[] out = PdfMetadataEditor.executeBytes(pdf, title, author, subject, keywords, strip);
            return new NamedBytes(MemoryOperations.outputName(OperationType.METADATA, in.filename()), out);
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }

    /** Batch-edit (or strip) every input's metadata, preserving order. */
    public List<NamedBytes> editMetadata(List<NamedBytes> inputs, String title, String author,
                                         String subject, String keywords, boolean strip)
            throws PdfOperationException {
        List<NamedBytes> out = new ArrayList<>(inputs.size());
        for (NamedBytes in : inputs) out.add(editMetadata(in, title, author, subject, keywords, strip));
        return out;
    }

    // --------------------------------------------------------------- WATERMARK

    public List<NamedBytes> watermark(List<NamedBytes> inputs, String text, byte[] image,
                                      double opacity, double rotation, double scale,
                                      com.pdfconduit.core.model.WatermarkOptions.Layout layout,
                                      com.pdfconduit.core.model.WatermarkOptions.Position position,
                                      String color)
            throws PdfOperationException {
        return MemoryOperations.runBatch(OperationType.WATERMARK, pdfData(inputs), names(inputs),
            pdf -> PdfWatermarker.executeBytes(pdf, text, image, opacity, rotation, scale,
                layout, position, color));
    }

    // -------------------------------------------------------------------- CROP

    /** Batch-crop every input, trimming the given per-edge margins (points, or mm when set). */
    public List<NamedBytes> crop(List<NamedBytes> inputs, double top, double right, double bottom,
                                 double left, boolean millimetres) throws PdfOperationException {
        return MemoryOperations.runBatch(OperationType.CROP, pdfData(inputs), names(inputs),
            pdf -> PdfCropper.executeBytes(pdf, top, right, bottom, left, millimetres));
    }

    // --------------------------------------------------------------------- NUP

    /** Batch N-up / booklet imposition: each input imposed to its own PDF, order preserved. */
    public List<NamedBytes> nup(List<NamedBytes> inputs,
                                com.pdfconduit.core.model.NupLayout layout, boolean booklet)
            throws PdfOperationException {
        return MemoryOperations.runBatch(OperationType.NUP, pdfData(inputs), names(inputs),
            pdf -> com.pdfconduit.core.operations.PdfNupImposer.executeBytes(pdf, layout, booklet));
    }

    // ------------------------------------------------------------------ REPAIR

    /**
     * Rebuild a damaged PDF, reporting what was actually wrong with it.
     *
     * <p>Repair is the one operation whose input must survive routing <em>unmodified</em>: the damage
     * lives in the upload's own byte structure, so re-encoding it first would destroy the very thing
     * being repaired. That holds here because {@link #routeToPdf} classifies by extension — a
     * {@code .pdf} upload is handed through byte-for-byte (an image/office upload is still converted,
     * under the office guard, and then trivially "repairs" to itself).
     *
     * <p>The page-count guard can only run <em>after</em> the rebuild, exactly like Unlock: a file we
     * cannot parse yet has no trustworthy page count to check.
     */
    public RepairBytesResult repair(NamedBytes in) throws PdfOperationException {
        RepairBytesResult r = PdfRepairer.executeBytes(routeToPdf(in));
        guardPageCountValue(r.pageCount());
        return r;
    }

    /** Batch repair: one rebuilt PDF per input, order preserved (per-file metrics are not zipped). */
    public List<NamedBytes> repairBatch(List<NamedBytes> inputs) throws PdfOperationException {
        List<NamedBytes> out = new ArrayList<>(inputs.size());
        for (NamedBytes in : inputs) out.add(new NamedBytes(repairedName(in), repair(in).bytes()));
        return out;
    }

    /** Output filename for a repaired upload — {@code <stem>_repaired.pdf}, from the catalog. */
    public static String repairedName(NamedBytes in) {
        return MemoryOperations.outputName(OperationType.REPAIR, in.filename());
    }

    // -------------------------------------------------------------- PAGE-MARKS

    /**
     * Batch-stamp page numbers and/or header/footer text onto every input. Each of the six slots
     * may carry the tokens {@code {page}}/{@code {n}}/{@code {pages}}/{@code {date}}; a non-blank
     * {@code numberPrefix} switches page numbers to Bates-style (prefix + zero-padded).
     */
    public List<NamedBytes> pageMarks(List<NamedBytes> inputs,
                                      String headerLeft, String headerCenter, String headerRight,
                                      String footerLeft, String footerCenter, String footerRight,
                                      float fontSize, float margin, boolean skipFirst,
                                      int startNumber, String numberPrefix)
            throws PdfOperationException {
        return MemoryOperations.runBatch(OperationType.PAGE_MARKS, pdfData(inputs), names(inputs),
            pdf -> PdfPageMarker.executeBytes(pdf, headerLeft, headerCenter, headerRight,
                footerLeft, footerCenter, footerRight, fontSize, margin, skipFirst,
                startNumber, numberPrefix));
    }

    // ------------------------------------------------------------------ REDACT

    /**
     * Permanently redact {@code regions} (rasterising the affected pages). When {@code reOcr} is
     * requested <em>and</em> OCR is available on this server, the rasterised output is piped back
     * through {@link PdfOcr} to re-add an invisible searchable text layer over the flattened pages:
     * the redacted content stays gone (it is now only black pixels), while the surviving text becomes
     * selectable/searchable again. Re-OCR is a best-effort enhancement layered <em>on top</em> of the
     * security-critical redaction — if OCR is disabled by config or Tesseract is not installed the
     * flag is a clean <b>no-op</b> (the redacted, non-searchable PDF is still returned), so an
     * optional searchability step can never discard or block the actual redaction.
     *
     * <p><b>Fails loudly.</b> An empty request is a 400 and every requested rectangle must actually
     * be painted — a region the redactor could not apply (out-of-range page, degenerate box, client
     * coordinate drift) aborts the request instead of streaming back an unredacted file under a
     * {@code *_redacted.pdf} name. The applied counts travel back to the controller, which puts them
     * in {@code X-Redacted-Pages} / {@code X-Redacted-Regions}.
     */
    public RedactOutcome redact(NamedBytes in, List<RedactRegion> regions, int dpi, boolean reOcr)
            throws PdfOperationException {
        requireRegions(regions);
        byte[] pdf = routeToPdf(in);
        // Only the pages carrying a region are rasterised — mirror PdfRedactor's grouping so both
        // the render budget below and the re-OCR pass below count exactly the pages that really get
        // rendered. (The core refuses a degenerate box outright, so this filter is belt and
        // braces; a zero-area region can never reach the redactor as a silent no-op.)
        Set<Integer> rasterised = new HashSet<>();
        for (RedactRegion r : regions) {
            if (r.width() > 0 && r.height() > 0) rasterised.add(r.pageIndex());
        }
        RedactBytesResult redacted;
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            // dpi <= 0 means "core default" (PdfRedactor.DEFAULT_DPI); guard against the effective value.
            outputBudget.checkPixels(
                guardRender(lp, dpi > 0 ? dpi : PdfRedactor.DEFAULT_DPI, rasterised::contains));
            redacted = PdfRedactor.executeBytes(pdf, regions, dpi);
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
        // Belt and braces: the core now rejects un-appliable regions outright, so this can only
        // fire if that contract ever regresses — and it must fire, not ship a quiet non-redaction.
        if (redacted.redactedRegions() < regions.size()) {
            throw new PdfOperationException("Only " + redacted.redactedRegions() + " of "
                + regions.size() + " redaction region(s) could be applied; nothing was returned.");
        }
        byte[] out = redacted.data();
        if (reOcr && ocrEnabled && PdfOcr.available()) {
            // Only the rasterised pages lost their text layer, so OCR touches exactly those pages.
            // The untouched pages keep their original, superior text layer instead of gaining a
            // duplicate invisible one, and each skipped page saves a full tesseract run.
            out = reOcr(out, rasterised);
        }
        return new RedactOutcome(
            new NamedBytes(MemoryOperations.outputName(OperationType.REDACT, in.filename()), out),
            redacted.redactedPages(), redacted.redactedRegions());
    }

    /**
     * A redaction request with no rectangles is refused (→ 400): running it would hand back a
     * byte-for-byte copy of the input named {@code *_redacted.pdf}, which is a lie about the file's
     * safety. (The core redactor still allows an empty list — it claims nothing.)
     */
    private static void requireRegions(List<RedactRegion> regions) {
        if (regions == null || regions.isEmpty()) {
            throw new IllegalArgumentException(
                "Nothing to redact: provide at least one region to black out.");
        }
    }

    /**
     * Re-adds a searchable text layer over the already-rasterised redacted {@code pages} (0-based),
     * reusing the exact OCR plumbing behind {@code /api/ocr} — render/page-count guarded, run under
     * {@link OcrGuard}'s concurrency + timeout gate. Callers gate on {@link PdfOcr#available()} first.
     */
    private byte[] reOcr(byte[] redacted, Set<Integer> pages) throws PdfOperationException {
        int maxDpi = maxDpi();
        int ocrDpi = maxDpi > 0 ? Math.min(PdfOcr.DEFAULT_DPI, maxDpi) : PdfOcr.DEFAULT_DPI;
        try (LoadedPdf lp = LoadedPdf.open(redacted)) {
            guardPageCount(lp);
            outputBudget.checkPixels(guardRender(lp, ocrDpi, pages::contains));
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read redacted PDF: " + e.getMessage(), e);
        }
        try {
            return ocrGuard.run(() -> PdfOcr.executeBytes(redacted, ocrLanguages, ocrDpi, pages));
        } catch (IOException e) {
            throw new PdfOperationException("OCR failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------- SIGN

    /**
     * Fill &amp; Sign (Phase 1, visual): stamp {@code signatureImages} at {@code placements}, fill any
     * AcroForm {@code fieldValues}, and optionally flatten the form. Input is routed to PDF first and
     * the page-count guard applied; drawing does not rasterise pages, so there is no render guard.
     */
    public NamedBytes sign(NamedBytes in, List<byte[]> signatureImages, List<SignPlacement> placements,
                           java.util.Map<String, String> fieldValues, boolean flatten)
            throws PdfOperationException {
        byte[] out = MemoryOperations.runSingle(in.data(), in.filename(),
            pdf -> {
                guardPageCount(pdf);
                return PdfSigner.executeBytes(pdf, signatureImages, placements, fieldValues, flatten);
            });
        return new NamedBytes(MemoryOperations.outputName(OperationType.SIGN, in.filename()), out);
    }

    // --------------------------------------------------------------------- OCR

    /**
     * OCR a single input into a <b>searchable</b> PDF (invisible text layer over the original page)
     * via the external {@code tesseract} binary. Rejected up front (415, {@code ocr_disabled}) when
     * OCR is disabled by config or Tesseract is not installed. Pages are rendered at
     * {@link PdfOcr#DEFAULT_DPI} (capped to the render ceiling) for recognition; the heavy
     * page-render + external-process work runs under {@link OcrGuard}'s concurrency + timeout gate.
     */
    public NamedBytes ocr(NamedBytes in, String languages) throws PdfOperationException {
        if (!ocrEnabled || !PdfOcr.available()) {
            throw new OcrDisabledException();
        }
        byte[] pdf = routeToPdf(in);
        int maxDpi = maxDpi();
        int ocrDpi = maxDpi > 0 ? Math.min(PdfOcr.DEFAULT_DPI, maxDpi) : PdfOcr.DEFAULT_DPI;
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            // OCR renders EVERY page, so the whole document counts against the render budget.
            outputBudget.checkPixels(guardRender(lp, ocrDpi));
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
        String lang = (languages == null || languages.isBlank()) ? ocrLanguages : languages.strip();
        try {
            byte[] out = ocrGuard.run(() -> PdfOcr.executeBytes(pdf, lang, ocrDpi));
            return new NamedBytes(MemoryOperations.outputName(OperationType.OCR, in.filename()), out);
        } catch (IOException e) {
            throw new PdfOperationException("OCR failed: " + e.getMessage(), e);
        }
    }

    // --------------------------------------------------------------- TO-IMAGES

    /** Render selected pages of a single input to images. */
    public List<NamedBytes> toImages(NamedBytes in, ImageFormat format, int dpi, String pagesExpr,
                                     float jpegQuality, boolean transparentBackground,
                                     boolean grayscale)
            throws PdfOperationException, InvalidPageRangeException {
        return toImages(List.of(in), format, dpi, pagesExpr, jpegQuality,
            transparentBackground, grayscale);
    }

    /**
     * Render selected pages of every input to images, concatenated. Each input's page images are
     * named from that input's filename ({@link #nameMulti}), so results from different source files
     * stay distinct; any residual name collision is de-duplicated when zipped.
     *
     * <p>Runs in two passes on purpose. The first pass guards every file (page count, per-page
     * render ceiling) and sums the pixel area of the pages that will really be rendered into ONE
     * per-request {@link OutputBudget} tally, so a request whose <em>total</em> is too big is
     * rejected before a single page is rasterised — {@code pages × files} is exactly what the
     * per-page ceilings do not bound. The second pass renders under a running byte ceiling, so
     * even a request that passes the pixel estimate aborts the moment its accumulated images
     * outgrow the budget, rather than OOM-ing on the final zip.
     */
    public List<NamedBytes> toImages(List<NamedBytes> inputs, ImageFormat format, int dpi,
                                     String pagesExpr, float jpegQuality,
                                     boolean transparentBackground, boolean grayscale)
            throws PdfOperationException, InvalidPageRangeException {
        return toImages(inputs, format, dpi, pagesExpr, jpegQuality, transparentBackground,
            grayscale, outputBudget.tally());
    }

    /**
     * As {@link #toImages(List, ImageFormat, int, String, float, boolean, boolean)} but against a
     * caller-supplied request tally, so a partial-tolerant batch — which runs one file per call —
     * still accumulates its result bytes across the whole request instead of restarting the budget
     * for every file.
     *
     * <p>An input {@link #prepareRender} has already routed carries PDF bytes and is used as-is, so
     * an office document is never converted twice; anything else is routed here exactly as before
     * (including a file that failed the pre-flight, which re-fails here with its own message).
     */
    public List<NamedBytes> toImages(List<NamedBytes> inputs, ImageFormat format, int dpi,
                                     String pagesExpr, float jpegQuality,
                                     boolean transparentBackground, boolean grayscale,
                                     OutputBudget.Tally tally)
            throws PdfOperationException, InvalidPageRangeException {
        // Route once (PDF uploads pass through unchanged — no extra copy) and keep the routed bytes
        // so the guard pass and the render pass agree on exactly the same document. Routing keys off
        // the file NAME, so already-routed bytes are recognised by their content instead.
        List<byte[]> pdfs = new ArrayList<>(inputs.size());
        for (NamedBytes in : inputs) {
            pdfs.add(FileTypeDetector.isPdf(in.data()) ? in.data() : routeToPdf(in));
        }

        List<PageRange> ranges = new ArrayList<>(inputs.size());
        for (byte[] pdf : pdfs) {
            try (LoadedPdf lp = LoadedPdf.open(pdf)) {
                guardPageCount(lp);
                PageRange pages = range(pagesExpr, lp);
                ranges.add(pages);
                tally.addPixels(guardRender(lp, dpi, rendered(pages)));
            } catch (IOException e) {
                throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
            }
        }

        List<NamedBytes> out = new ArrayList<>();
        for (int i = 0; i < pdfs.size(); i++) {
            List<byte[]> images = PdfToImageConverter.executeBytes(pdfs.get(i), format, dpi,
                ranges.get(i), jpegQuality, transparentBackground, grayscale, tally.guard());
            tally.commit(images);
            out.addAll(nameMulti(OperationType.PDF_TO_IMAGES, inputs.get(i).filename(), images,
                format.extension()));
        }
        return out;
    }

    /**
     * A fresh output tally for ONE request. A partial-tolerant batch runs each file through its own
     * call, so the request's ceiling only stays a request ceiling if the caller creates the tally
     * once here and passes the same one to every file.
     */
    public OutputBudget.Tally newOutputTally() {
        return outputBudget.tally();
    }

    /**
     * Whole-request pre-flight for a partial-tolerant batch render: routes every input to PDF once,
     * guards it (page count, per-page render ceiling) and sums the pixel area the request will
     * really rasterise into ONE tally — so a batch whose <em>total</em> is over the ceiling is
     * rejected <b>before a single page is rendered</b>, which is precisely what the per-page
     * ceilings cannot do.
     *
     * <p>Returns the routed uploads under their original filenames (the output names are built from
     * the stem, so nothing is renamed), which is what lets the per-file pass that follows re-route
     * nothing: an office document is converted exactly once, not twice. An input that cannot be
     * routed or read is returned untouched and simply not counted — the per-file pass reproduces
     * its failure and names it in {@code X-Batch-Failures}, so a per-file defect is never escalated
     * into a whole-request error here. A blown budget is the opposite: it describes the request, so
     * it propagates (422).
     */
    public List<NamedBytes> prepareRender(List<NamedBytes> inputs, int dpi, String pagesExpr)
            throws PdfOperationException, InvalidPageRangeException {
        OutputBudget.Tally preflight = outputBudget.tally();
        List<NamedBytes> routed = new ArrayList<>(inputs.size());
        for (NamedBytes in : inputs) {
            try {
                byte[] pdf = routeToPdf(in);
                try (LoadedPdf lp = LoadedPdf.open(pdf)) {
                    guardPageCount(lp);
                    preflight.addPixels(guardRender(lp, dpi, rendered(range(pagesExpr, lp))));
                } catch (IOException e) {
                    throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
                }
                routed.add(new NamedBytes(in.filename(), pdf));
            } catch (OutputTooLargeException e) {
                throw e;                       // the SUM is too big — not this file's fault
            } catch (PdfOperationException e) {
                routed.add(in);                // left to the per-file pass, which names it
            }
        }
        return routed;
    }

    // ---------------------------------------------------------------- TO-TEXT

    public NamedBytes toText(NamedBytes in, TextFormat format, String pagesExpr)
            throws PdfOperationException, InvalidPageRangeException {
        // DOCX output is now built in memory (OOXML, no LibreOffice), so it is never office-gated.
        // Non-PDF *inputs* are still routed through routeToPdf, which applies the office gate itself.
        byte[] pdf = routeToPdf(in);
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            byte[] out = PdfTextExporter.toTextBytes(pdf, format, range(pagesExpr, lp));
            String name = stem(in.filename()) + OperationType.PDF_TO_TEXT.suffix() + "." + format.extension();
            return new NamedBytes(name, out);
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }

    /** Batch-export every input to text/docx, preserving order. */
    public List<NamedBytes> toText(List<NamedBytes> inputs, TextFormat format, String pagesExpr)
            throws PdfOperationException, InvalidPageRangeException {
        List<NamedBytes> out = new ArrayList<>(inputs.size());
        for (NamedBytes in : inputs) out.add(toText(in, format, pagesExpr));
        return out;
    }

    // -------------------------------------------------------------- FORM-FIELDS

    /**
     * Enumerate an input's fillable AcroForm fields (read-only detection). The upload is routed to
     * PDF first (images/office → PDF, then page-count guarded) so a non-PDF simply yields no fields;
     * enumeration runs off the one open handle. Nothing is stored.
     */
    public List<com.pdfconduit.core.model.FormField> listFormFields(NamedBytes in)
            throws PdfOperationException {
        byte[] pdf = routeToPdf(in);
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            return PdfSigner.listFields(lp.document());
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }

    // --------------------------------------------------------------- GDPR-SCAN

    /**
     * Scan an input for GDPR-relevant personal data. Non-PDF inputs (images/office) are routed to
     * PDF first via {@link #toPdf} (which also enforces the page-count guard), then handed to the
     * offline {@link PiiScanner}. Nothing is stored; the scan runs entirely in memory.
     */
    public PiiScanResult scanPii(NamedBytes in) throws PdfOperationException {
        return PiiScanner.scanBytes(toPdf(in));
    }

    /**
     * Batch GDPR scan: scan every input for personal data, preserving order. Each file is routed to
     * PDF and page-count-guarded via {@link #scanPii} before the offline {@link PiiScanner} runs.
     * Nothing is stored; the whole batch is scanned in memory and the aggregate report is built by
     * the caller from the per-file results.
     */
    public List<PiiScanResult> scanPiiBatch(List<NamedBytes> inputs) throws PdfOperationException {
        List<PiiScanResult> out = new ArrayList<>(inputs.size());
        for (NamedBytes in : inputs) out.add(scanPii(in));
        return out;
    }

    // ---------------------------------------------------------------- AUTO-REDACT

    /**
     * One-click auto-redaction driven by the PII scan: scan the input offline, collect every
     * concrete-value finding's on-page regions (optionally limited to {@code categories}) and feed
     * them straight into {@link PdfRedactor} — the finding regions already live in the redactor's
     * coordinate space, so this is a coordinate-compatible, nearly-free hand-off. The value is
     * permanently rasterised away (see {@link PdfRedactor}), not merely covered. Special-category
     * keyword flags carry no regions, so they are never "redacted" (nothing to black out).
     *
     * <p>When the filtered scan yields <b>no</b> rectangles — an unredactable document, or a scan
     * whose only hits are Art. 9 keyword flags, which carry no coordinates — the request is refused
     * (→ 422 {@code operation_failed}) instead of returning the untouched upload named
     * {@code *_redacted.pdf}. A file that claims to be redacted must have been redacted.
     *
     * @param categories when non-empty, only findings in these GDPR categories are redacted;
     *                   empty/{@code null} ⇒ redact every detected value.
     */
    public RedactOutcome autoRedact(NamedBytes in, Set<PiiCategory> categories)
            throws PdfOperationException {
        byte[] pdf = routeToPdf(in);
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            guardRender(lp, PdfRedactor.DEFAULT_DPI);
            PiiScanResult scan = PiiScanner.scanBytes(pdf);
            List<RedactRegion> regions = new ArrayList<>();
            Set<Integer> rasterised = new HashSet<>();
            for (PiiFinding f : scan.findings()) {
                if (categories != null && !categories.isEmpty() && !categories.contains(f.category())) {
                    continue;
                }
                for (PiiRegion r : f.regions()) {
                    regions.add(new RedactRegion(r.page(), r.x(), r.y(), r.width(), r.height()));
                    if (r.width() > 0 && r.height() > 0) rasterised.add(r.page());
                }
            }
            if (regions.isEmpty()) {
                throw new PdfOperationException("Nothing could be redacted: the scan found no values "
                    + "with a location on the page. Keyword-flagged categories (e.g. health, "
                    + "religion) carry no coordinates, so there is no box to black out — "
                    + "use Redact and draw the areas yourself.");
            }
            // The findings decide which pages get rasterised, so the render budget can only be
            // settled here — still before PdfRedactor renders anything.
            outputBudget.checkPixels(
                guardRender(lp, PdfRedactor.DEFAULT_DPI, rasterised::contains));
            RedactBytesResult redacted = PdfRedactor.executeBytes(pdf, regions, 0);
            if (redacted.redactedRegions() < regions.size()) {
                throw new PdfOperationException("Only " + redacted.redactedRegions() + " of "
                    + regions.size() + " detected value(s) could be blacked out; nothing was returned.");
            }
            return new RedactOutcome(
                new NamedBytes(MemoryOperations.outputName(OperationType.REDACT, in.filename()),
                    redacted.data()),
                redacted.redactedPages(), redacted.redactedRegions());
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ RENDER

    /** Render a single 0-based page of the input to a PNG (for pdf.js fallback / thumbnails). */
    public byte[] renderPage(NamedBytes in, int pageIndex, int dpi)
            throws PdfOperationException, InvalidPageRangeException {
        byte[] pdf = routeToPdf(in);
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            // Exactly one page is rendered, so only that page counts against the render budget.
            outputBudget.checkPixels(guardRender(lp, dpi, i -> i == pageIndex));
            PageRange page = PageRangeParser.parse(String.valueOf(pageIndex + 1), lp.pageCount());
            List<byte[]> images = PdfToImageConverter.executeBytes(pdf, ImageFormat.PNG, dpi, page, 1f);
            return images.get(0);
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }

    // --------------------------------------------------------------- internals

    /**
     * Routes an upload to PDF bytes (office conversion gated by {@link OfficeGuard}) and enforces
     * the PDF page-count ceiling. The single chokepoint every single-file operation flows through.
     */
    private byte[] toPdf(NamedBytes in) throws PdfOperationException {
        byte[] pdf = routeToPdf(in);
        guardPageCount(pdf);
        return pdf;
    }

    /**
     * Routes an upload to PDF bytes (office conversion gated by {@link OfficeGuard}) <em>without</em>
     * the page-count guard, so a single-file caller can open the routed bytes once (as a
     * {@link LoadedPdf}) and run every read-only guard/range check off that one parse.
     */
    private byte[] routeToPdf(NamedBytes in) throws PdfOperationException {
        try {
            return officeGuard.run(in.filename(),
                () -> MemoryOperations.toPdfBytes(in.data(), in.filename()));
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read input: " + e.getMessage(), e);
        }
    }

    /**
     * Converts + guards a batch of uploads to PDF bytes, preserving order (names kept by caller).
     * A file that cannot be routed or is over the page cap fails with its own name in the message —
     * the in-memory loaders have no file name of their own, and "the PDF is password-protected" is
     * useless when fifteen were uploaded.
     */
    private List<byte[]> pdfData(List<NamedBytes> inputs) throws PdfOperationException {
        List<byte[]> out = new ArrayList<>(inputs.size());
        for (NamedBytes in : inputs) {
            try {
                out.add(toPdf(in));
            } catch (PdfOperationException e) {
                throw MemoryOperations.named(in.filename(), e);
            }
        }
        return out;
    }

    /**
     * Raster-render guard (render / to-images / redact / ocr) for a document whose every page will
     * be rasterised. See {@link #guardRender(LoadedPdf, int, IntPredicate)}.
     */
    private long guardRender(LoadedPdf lp, int dpi) throws PdfOperationException {
        return guardRender(lp, dpi, null);
    }

    /**
     * Raster-render guard: reject a DPI above the configured ceiling (→ 400) and any page whose
     * rendered pixel area would exceed the per-page {@code maxOutputPixels} (→ 422), BEFORE any
     * page is rasterised. The per-page check still covers <em>every</em> page of the document,
     * unchanged.
     *
     * <p>Per-page ceilings alone do not bound {@code pages × files}: the summed area of the pages
     * this call will actually render is returned so the caller can accumulate it into the
     * per-request {@link OutputBudget} — the guard that stops a legal-looking 800-page 300 DPI
     * render from allocating more than the whole heap.
     *
     * @param rendered 0-based page indices that will really be rasterised; {@code null} ⇒ all pages
     * @return summed pixel area of the pages that will be rendered
     */
    private long guardRender(LoadedPdf lp, int dpi, IntPredicate rendered)
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

    /** The 0-based pages a {@link PageRange} selects, as a predicate ({@code null} ⇒ all pages). */
    private static IntPredicate rendered(PageRange pages) {
        if (pages == null || pages.isAll()) return null;
        Set<Integer> selected = new HashSet<>();
        for (int pageNum : pages.pageNumbers()) selected.add(pageNum - 1);
        return selected::contains;
    }

    /** PDF-bomb guard: reject a PDF whose page count exceeds the configured ceiling (→ 422). */
    private void guardPageCount(byte[] pdf) throws PdfOperationException {
        if (maxPages() <= 0) return;
        guardPageCountValue(pageCount(pdf));
    }

    /** As {@link #guardPageCount(byte[])} but off an already-open handle — no re-parse. */
    private void guardPageCount(LoadedPdf lp) throws PdfOperationException {
        guardPageCountValue(lp.pageCount());
    }

    /**
     * The page-count ceiling check on an already-known page count. Also the
     * {@link PdfCompressor.PageCountGuard} for compress, so its lossless-pass parse doubles as the
     * guard parse (no separate page-count parse).
     */
    private void guardPageCountValue(int pageCount) throws PdfOperationException {
        int maxPages = maxPages();
        if (maxPages > 0 && pageCount > maxPages) {
            throw new PdfOperationException("PDF exceeds the maximum page count (" + maxPages + ").");
        }
    }

    private PageRange range(String expr, byte[] pdf)
            throws PdfOperationException, InvalidPageRangeException {
        if (expr == null || expr.isBlank()) return PageRange.ALL;
        return PageRangeParser.parse(expr, pageCount(pdf));
    }

    /** As {@link #range(String, byte[])} but off an already-open handle — no re-parse. */
    private PageRange range(String expr, LoadedPdf lp)
            throws PdfOperationException, InvalidPageRangeException {
        if (expr == null || expr.isBlank()) return PageRange.ALL;
        return PageRangeParser.parse(expr, lp.pageCount());
    }

    private int pageCount(byte[] pdf) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }

    /** Names a multi-output result {@code <stem><suffix>.ext} / {@code <stem><suffix>_N.ext}. */
    private static List<NamedBytes> nameMulti(OperationType type, String filename,
                                              List<byte[]> parts, String ext) {
        String stem = stem(filename);
        List<NamedBytes> out = new ArrayList<>(parts.size());
        int width = Integer.toString(Math.max(1, parts.size())).length();
        for (int i = 0; i < parts.size(); i++) {
            String name = parts.size() == 1
                ? stem + type.suffix() + "." + ext
                : stem + type.suffix() + "_" + pad(i + 1, width) + "." + ext;
            out.add(new NamedBytes(name, parts.get(i)));
        }
        return out;
    }

    private static List<byte[]> data(List<NamedBytes> inputs) {
        return inputs.stream().map(NamedBytes::data).toList();
    }

    private static List<String> names(List<NamedBytes> inputs) {
        return inputs.stream().map(NamedBytes::filename).toList();
    }

    private static String stem(String filename) {
        return com.pdfconduit.core.util.Filenames.stem(filename);
    }

    private static String pad(int n, int width) {
        return String.format("%0" + Math.max(1, width) + "d", n);
    }
}
