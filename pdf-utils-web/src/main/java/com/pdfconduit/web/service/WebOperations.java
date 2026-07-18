package com.pdfconduit.web.service;

import com.pdfconduit.core.analyze.PiiScanResult;
import com.pdfconduit.core.analyze.PiiScanner;
import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.CompressBytesResult;
import com.pdfconduit.core.model.ImageFormat;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.PdfMetadata;
import com.pdfconduit.core.model.RedactRegion;
import com.pdfconduit.core.model.TextFormat;
import com.pdfconduit.core.operations.PdfArranger;
import com.pdfconduit.core.operations.PdfCompressor;
import com.pdfconduit.core.operations.PdfMerger;
import com.pdfconduit.core.operations.PdfMetadataEditor;
import com.pdfconduit.core.operations.PdfProtector;
import com.pdfconduit.core.operations.PdfRedactor;
import com.pdfconduit.core.operations.PdfRotator;
import com.pdfconduit.core.operations.PdfSplitter;
import com.pdfconduit.core.operations.PdfTextExporter;
import com.pdfconduit.core.operations.PdfToImageConverter;
import com.pdfconduit.core.operations.PdfUnlocker;
import com.pdfconduit.core.operations.PdfWatermarker;
import com.pdfconduit.core.service.MemoryOperations;
import com.pdfconduit.core.service.NamedBytes;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.core.util.LoadedPdf;
import com.pdfconduit.core.util.PageOrderParser;
import com.pdfconduit.core.util.PageRangeParser;
import com.pdfconduit.core.util.PdfLoader;
import com.pdfconduit.core.operations.PdfRedactor;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.error.OfficeDisabledException;
import com.pdfconduit.web.guard.OfficeGuard;
import com.pdfconduit.web.plan.PlanLimits;
import com.pdfconduit.web.plan.PlanLimitsResolver;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
    private final int maxPages;
    private final int maxDpi;
    private final long maxOutputPixels;
    private final boolean officeEnabled;

    public WebOperations(OfficeGuard officeGuard, PlanLimitsResolver planLimits, WebProperties props) {
        this.officeGuard = officeGuard;
        // Page-count and render ceilings are read from the resolved plan (today the constant FREE
        // plan built from WebProperties, so identical values); office availability stays a
        // system-level WebProperties toggle. The service guards by value with no request in scope,
        // so it resolves the default plan — a later per-principal plan would be threaded in here.
        PlanLimits plan = planLimits.resolveDefault();
        this.maxPages = plan.maxPages();
        this.maxDpi = plan.maxDpi();
        this.maxOutputPixels = plan.maxOutputPixels();
        this.officeEnabled = props.officeEnabled();
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
        byte[] pdf = routeToPdf(in);
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            List<byte[]> pages = PdfSplitter.separateBytes(pdf, range(pagesExpr, lp));
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
        List<NamedBytes> out = new ArrayList<>();
        for (NamedBytes in : inputs) out.addAll(extractSeparate(in, pagesExpr));
        return out;
    }

    // --------------------------------------------------------------- COMPRESS

    /** Compress a single input to (at most) {@code targetBytes}; full metrics returned. */
    public CompressBytesResult compress(NamedBytes in, long targetBytes) throws PdfOperationException {
        // Single-parse: skip the separate page-count-guard parse and fold the guard into the
        // document the compressor's lossless pass already opens (see PdfCompressor.PageCountGuard).
        return PdfCompressor.compressBytes(routeToPdf(in), targetBytes, this::guardPageCountValue);
    }

    /** Batch-compress every input to (at most) {@code targetBytes}. */
    public List<NamedBytes> compressBatch(List<NamedBytes> inputs, long targetBytes)
            throws PdfOperationException {
        return MemoryOperations.runBatch(OperationType.COMPRESS, pdfData(inputs), names(inputs),
            pdf -> PdfCompressor.compressBytes(pdf, targetBytes).bytes());
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

    /** Reorder a single input's pages per {@code order} (e.g. {@code 3,1,2}). */
    public NamedBytes arrange(NamedBytes in, String order)
            throws PdfOperationException, InvalidPageRangeException {
        byte[] pdf = routeToPdf(in);
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            List<Integer> pageOrder = PageOrderParser.parse(order, lp.pageCount());
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

    // ------------------------------------------------------------------ REDACT

    public NamedBytes redact(NamedBytes in, List<RedactRegion> regions, int dpi)
            throws PdfOperationException {
        byte[] pdf = routeToPdf(in);
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            // dpi <= 0 means "core default" (PdfRedactor.DEFAULT_DPI); guard against the effective value.
            guardRender(lp, dpi > 0 ? dpi : PdfRedactor.DEFAULT_DPI);
            byte[] out = PdfRedactor.executeBytes(pdf, regions, dpi);
            return new NamedBytes(MemoryOperations.outputName(OperationType.REDACT, in.filename()), out);
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }

    // --------------------------------------------------------------- TO-IMAGES

    /** Render selected pages of a single input to images. */
    public List<NamedBytes> toImages(NamedBytes in, ImageFormat format, int dpi, String pagesExpr,
                                     float jpegQuality)
            throws PdfOperationException, InvalidPageRangeException {
        byte[] pdf = routeToPdf(in);
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            guardRender(lp, dpi);
            List<byte[]> images = PdfToImageConverter.executeBytes(pdf, format, dpi,
                range(pagesExpr, lp), jpegQuality);
            return nameMulti(OperationType.PDF_TO_IMAGES, in.filename(), images, format.extension());
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Render selected pages of every input to images, concatenated. Each input's page images are
     * named from that input's filename ({@link #nameMulti}), so results from different source files
     * stay distinct; any residual name collision is de-duplicated when zipped.
     */
    public List<NamedBytes> toImages(List<NamedBytes> inputs, ImageFormat format, int dpi,
                                     String pagesExpr, float jpegQuality)
            throws PdfOperationException, InvalidPageRangeException {
        List<NamedBytes> out = new ArrayList<>();
        for (NamedBytes in : inputs) out.addAll(toImages(in, format, dpi, pagesExpr, jpegQuality));
        return out;
    }

    // ---------------------------------------------------------------- TO-TEXT

    public NamedBytes toText(NamedBytes in, TextFormat format, String pagesExpr)
            throws PdfOperationException, InvalidPageRangeException {
        // DOCX output needs LibreOffice (txt→docx); reject up front when office is disabled so the
        // output-side conversion never fires an ungated soffice, mirroring the input-side 415.
        if (format == TextFormat.DOCX && !officeEnabled) {
            throw new OfficeDisabledException(in.filename());
        }
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

    // --------------------------------------------------------------- GDPR-SCAN

    /**
     * Scan an input for GDPR-relevant personal data. Non-PDF inputs (images/office) are routed to
     * PDF first via {@link #toPdf} (which also enforces the page-count guard), then handed to the
     * offline {@link PiiScanner}. Nothing is stored; the scan runs entirely in memory.
     */
    public PiiScanResult scanPii(NamedBytes in) throws PdfOperationException {
        return PiiScanner.scanBytes(toPdf(in));
    }

    // ------------------------------------------------------------------ RENDER

    /** Render a single 0-based page of the input to a PNG (for pdf.js fallback / thumbnails). */
    public byte[] renderPage(NamedBytes in, int pageIndex, int dpi)
            throws PdfOperationException, InvalidPageRangeException {
        byte[] pdf = routeToPdf(in);
        try (LoadedPdf lp = LoadedPdf.open(pdf)) {
            guardPageCount(lp);
            guardRender(lp, dpi);
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

    /** Converts + guards a batch of uploads to PDF bytes, preserving order (names kept by caller). */
    private List<byte[]> pdfData(List<NamedBytes> inputs) throws PdfOperationException {
        List<byte[]> out = new ArrayList<>(inputs.size());
        for (NamedBytes in : inputs) out.add(toPdf(in));
        return out;
    }

    /**
     * Raster-render guard (render / to-images / redact): reject a DPI above the configured ceiling
     * (→ 400) and any page whose rendered pixel area would exceed {@code maxOutputPixels} (→ 422),
     * BEFORE any page is rasterised. Together these bound the memory a single render can allocate,
     * so a huge {@code dpi} or an enormous page cannot OOM the JVM. {@code dpi} is the effective
     * (already-defaulted, positive) value.
     */
    private void guardRender(LoadedPdf lp, int dpi) throws PdfOperationException {
        if (maxDpi > 0 && dpi > maxDpi) {
            throw new IllegalArgumentException(
                "Requested DPI " + dpi + " exceeds the maximum allowed (" + maxDpi + ").");
        }
        if (maxOutputPixels <= 0) return;
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
    }

    /** PDF-bomb guard: reject a PDF whose page count exceeds the configured ceiling (→ 422). */
    private void guardPageCount(byte[] pdf) throws PdfOperationException {
        if (maxPages <= 0) return;
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
        String name = (filename == null || filename.isBlank()) ? "file" : filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem.isBlank() ? "file" : stem;
    }

    private static String pad(int n, int width) {
        return String.format("%0" + Math.max(1, width) + "d", n);
    }
}
