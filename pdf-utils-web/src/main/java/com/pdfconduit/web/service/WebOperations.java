package com.pdfconduit.web.service;

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
import com.pdfconduit.core.util.PageOrderParser;
import com.pdfconduit.core.util.PageRangeParser;
import com.pdfconduit.core.util.PdfLoader;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.guard.OfficeGuard;
import org.apache.pdfbox.pdmodel.PDDocument;
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

    public WebOperations(OfficeGuard officeGuard, WebProperties props) {
        this.officeGuard = officeGuard;
        this.maxPages = props.pdf().maxPages();
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
        byte[] pdf = toPdf(in);
        byte[] out = PdfSplitter.combineBytes(pdf, range(pagesExpr, pdf));
        return new NamedBytes(MemoryOperations.outputName(OperationType.EXTRACT, in.filename()), out);
    }

    /** Extract {@code pagesExpr} (blank ⇒ all) as one PDF per page. */
    public List<NamedBytes> extractSeparate(NamedBytes in, String pagesExpr)
            throws PdfOperationException, InvalidPageRangeException {
        byte[] pdf = toPdf(in);
        List<byte[]> pages = PdfSplitter.separateBytes(pdf, range(pagesExpr, pdf));
        return nameMulti(OperationType.EXTRACT, in.filename(), pages, "pdf");
    }

    // --------------------------------------------------------------- COMPRESS

    /** Compress a single input to (at most) {@code targetBytes}; full metrics returned. */
    public CompressBytesResult compress(NamedBytes in, long targetBytes) throws PdfOperationException {
        return PdfCompressor.compressBytes(toPdf(in), targetBytes);
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
        byte[] pdf = toPdf(in);
        List<Integer> pageOrder = PageOrderParser.parse(order, pageCount(pdf));
        byte[] out = PdfArranger.executeBytes(pdf, pageOrder);
        return new NamedBytes(MemoryOperations.outputName(OperationType.ARRANGE, in.filename()), out);
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

    public List<NamedBytes> protect(List<NamedBytes> inputs, String userPassword, String ownerPassword)
            throws PdfOperationException {
        return MemoryOperations.runBatch(OperationType.PROTECT, pdfData(inputs), names(inputs),
            pdf -> PdfProtector.executeBytes(pdf, userPassword, ownerPassword));
    }

    // ------------------------------------------------------------------ UNLOCK

    public List<NamedBytes> unlock(List<NamedBytes> inputs, String password)
            throws PdfOperationException {
        // Unlock operates on the raw (still-encrypted) upload; office/page guards don't apply here.
        return MemoryOperations.runBatch(OperationType.UNLOCK, data(inputs), names(inputs),
            pdf -> PdfUnlocker.executeBytes(pdf, password));
    }

    // ---------------------------------------------------------------- METADATA

    /** Read a PDF's document-info metadata (input may be office/image → converted first). */
    public PdfMetadata readMetadata(NamedBytes in) throws PdfOperationException {
        return PdfMetadataEditor.readBytes(toPdf(in));
    }

    /** Edit (or strip) a PDF's metadata; null field = unchanged, empty = cleared. */
    public NamedBytes editMetadata(NamedBytes in, String title, String author, String subject,
                                   String keywords, boolean strip) throws PdfOperationException {
        byte[] out = PdfMetadataEditor.executeBytes(toPdf(in), title, author, subject, keywords, strip);
        return new NamedBytes(MemoryOperations.outputName(OperationType.METADATA, in.filename()), out);
    }

    // --------------------------------------------------------------- WATERMARK

    public List<NamedBytes> watermark(List<NamedBytes> inputs, String text, byte[] image,
                                      double opacity, double rotation, double scale)
            throws PdfOperationException {
        return MemoryOperations.runBatch(OperationType.WATERMARK, pdfData(inputs), names(inputs),
            pdf -> PdfWatermarker.executeBytes(pdf, text, image, opacity, rotation, scale));
    }

    // ------------------------------------------------------------------ REDACT

    public NamedBytes redact(NamedBytes in, List<RedactRegion> regions, int dpi)
            throws PdfOperationException {
        byte[] out = PdfRedactor.executeBytes(toPdf(in), regions, dpi);
        return new NamedBytes(MemoryOperations.outputName(OperationType.REDACT, in.filename()), out);
    }

    // --------------------------------------------------------------- TO-IMAGES

    /** Render selected pages of a single input to images. */
    public List<NamedBytes> toImages(NamedBytes in, ImageFormat format, int dpi, String pagesExpr,
                                     float jpegQuality)
            throws PdfOperationException, InvalidPageRangeException {
        byte[] pdf = toPdf(in);
        List<byte[]> images = PdfToImageConverter.executeBytes(pdf, format, dpi,
            range(pagesExpr, pdf), jpegQuality);
        return nameMulti(OperationType.PDF_TO_IMAGES, in.filename(), images, format.extension());
    }

    // ---------------------------------------------------------------- TO-TEXT

    public NamedBytes toText(NamedBytes in, TextFormat format, String pagesExpr)
            throws PdfOperationException, InvalidPageRangeException {
        byte[] pdf = toPdf(in);
        byte[] out = PdfTextExporter.toTextBytes(pdf, format, range(pagesExpr, pdf));
        String name = stem(in.filename()) + OperationType.PDF_TO_TEXT.suffix() + "." + format.extension();
        return new NamedBytes(name, out);
    }

    // ------------------------------------------------------------------ RENDER

    /** Render a single 0-based page of the input to a PNG (for pdf.js fallback / thumbnails). */
    public byte[] renderPage(NamedBytes in, int pageIndex, int dpi)
            throws PdfOperationException, InvalidPageRangeException {
        byte[] pdf = toPdf(in);
        PageRange page = PageRangeParser.parse(String.valueOf(pageIndex + 1), pageCount(pdf));
        List<byte[]> images = PdfToImageConverter.executeBytes(pdf, ImageFormat.PNG, dpi, page, 1f);
        return images.get(0);
    }

    // --------------------------------------------------------------- internals

    /**
     * Routes an upload to PDF bytes (office conversion gated by {@link OfficeGuard}) and enforces
     * the PDF page-count ceiling. The single chokepoint every single-file operation flows through.
     */
    private byte[] toPdf(NamedBytes in) throws PdfOperationException {
        byte[] pdf;
        try {
            pdf = officeGuard.run(in.filename(),
                () -> MemoryOperations.toPdfBytes(in.data(), in.filename()));
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read input: " + e.getMessage(), e);
        }
        guardPageCount(pdf);
        return pdf;
    }

    /** Converts + guards a batch of uploads to PDF bytes, preserving order (names kept by caller). */
    private List<byte[]> pdfData(List<NamedBytes> inputs) throws PdfOperationException {
        List<byte[]> out = new ArrayList<>(inputs.size());
        for (NamedBytes in : inputs) out.add(toPdf(in));
        return out;
    }

    /** PDF-bomb guard: reject a PDF whose page count exceeds the configured ceiling (→ 422). */
    private void guardPageCount(byte[] pdf) throws PdfOperationException {
        if (maxPages <= 0) return;
        if (pageCount(pdf) > maxPages) {
            throw new PdfOperationException("PDF exceeds the maximum page count (" + maxPages + ").");
        }
    }

    private PageRange range(String expr, byte[] pdf)
            throws PdfOperationException, InvalidPageRangeException {
        if (expr == null || expr.isBlank()) return PageRange.ALL;
        return PageRangeParser.parse(expr, pageCount(pdf));
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
