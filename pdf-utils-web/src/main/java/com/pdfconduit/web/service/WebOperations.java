package com.pdfconduit.web.service;

import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.ArrangeOptions;
import com.pdfconduit.core.model.CompressOptions;
import com.pdfconduit.core.model.CompressResult;
import com.pdfconduit.core.model.ImageFormat;
import com.pdfconduit.core.model.MergeOptions;
import com.pdfconduit.core.model.MetadataOptions;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.PageSource;
import com.pdfconduit.core.model.PdfMetadata;
import com.pdfconduit.core.model.PdfToImageOptions;
import com.pdfconduit.core.model.PdfToImageResult;
import com.pdfconduit.core.model.PdfToTextOptions;
import com.pdfconduit.core.model.PdfToTextResult;
import com.pdfconduit.core.model.ProtectOptions;
import com.pdfconduit.core.model.RedactOptions;
import com.pdfconduit.core.model.RedactRegion;
import com.pdfconduit.core.model.RotateOptions;
import com.pdfconduit.core.model.SplitMode;
import com.pdfconduit.core.model.SplitOptions;
import com.pdfconduit.core.model.SplitResult;
import com.pdfconduit.core.model.TextFormat;
import com.pdfconduit.core.model.UnlockOptions;
import com.pdfconduit.core.model.WatermarkOptions;
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
import com.pdfconduit.core.service.Execution;
import com.pdfconduit.core.service.OperationRunner;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.core.service.ProgressSink;
import com.pdfconduit.core.util.PageOrderParser;
import com.pdfconduit.core.util.PageRangeParser;
import com.pdfconduit.web.support.Uploads;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The bridge from HTTP to {@code pdf-utils-core}: one method per operation, taking
 * already-saved input {@link Path}s + params and returning result {@link Path}(s).
 *
 * <p>All the heavy lifting is reused from core: {@link OperationRunner#run} for
 * single-input MAP ops (office/image inputs auto-convert), {@link OperationRunner#runBatch}
 * for multi-file batches (data-safety: never clobber a source, per-file failures collected),
 * and {@link PdfMerger} directly for the REDUCE merge. Output names come from
 * {@link OperationType#suffix()} — never hard-coded.
 */
@Service
public class WebOperations {

    // ------------------------------------------------------------------ MERGE

    /** Merge many inputs (pdf/image/office) into one PDF; returns the written file. */
    public Path merge(List<Path> inputs, PageSize imageSize, Path output) throws PdfOperationException {
        List<Path> temps = new ArrayList<>();
        try {
            List<PageSource> sources = Uploads.toPageSources(inputs, imageSize, temps);
            return PdfMerger.execute(new MergeOptions(sources, output)).output();
        } finally {
            Uploads.deleteTemps(temps);
        }
    }

    // ---------------------------------------------------------------- EXTRACT

    /** Extract {@code pagesExpr} (blank ⇒ all); combine into one PDF or one file per page. */
    public List<Path> extract(Path file, String pagesExpr, boolean separate, Path combineOutput, Path separateDir)
            throws PdfOperationException, InvalidPageRangeException {
        Path out = separate ? separateDir : combineOutput;
        SplitResult result = run(file, out, (pdf, o) -> {
            PageRange range = parseRange(pagesExpr, pdf);
            SplitMode mode = separate ? SplitMode.SEPARATE : SplitMode.COMBINE;
            return PdfSplitter.execute(new SplitOptions(pdf, range, mode, o));
        });
        return result.outputs();
    }

    // --------------------------------------------------------------- COMPRESS

    /** Compress a single input to (at most) {@code targetBytes}; full metrics returned. */
    public CompressResult compress(Path file, long targetBytes, Path output)
            throws PdfOperationException, InvalidPageRangeException {
        return run(file, output, (pdf, o) -> PdfCompressor.execute(new CompressOptions(pdf, targetBytes, o)));
    }

    /** Batch-compress every input to (at most) {@code targetBytes} (data-safety naming). */
    public OperationRunner.BatchOutcome compressBatch(List<Path> files, long targetBytes, Path outputDir)
            throws PdfOperationException {
        return runBatch(OperationType.COMPRESS, files, outputDir,
            (pdf, out) -> PdfCompressor.execute(new CompressOptions(pdf, targetBytes, out)));
    }

    // ----------------------------------------------------------------- ROTATE

    /** Batch-rotate every input by {@code angle} over {@code pagesExpr} (blank ⇒ all). */
    public OperationRunner.BatchOutcome rotate(List<Path> files, String pagesExpr, int angle, Path outputDir)
            throws PdfOperationException {
        return runBatch(OperationType.ROTATE, files, outputDir, (pdf, out) -> {
            PageRange range = parseRange(pagesExpr, pdf);
            return PdfRotator.execute(new RotateOptions(pdf, range, angle, out));
        });
    }

    // ---------------------------------------------------------------- ARRANGE

    /** Reorder a single input's pages per {@code order} (e.g. {@code 3,1,2}). */
    public Path arrange(Path file, String order, Path output)
            throws PdfOperationException, InvalidPageRangeException {
        return run(file, output, (pdf, o) -> {
            int total = Uploads.countPages(pdf);
            List<Integer> pageOrder = PageOrderParser.parse(order, total);
            return PdfArranger.execute(new ArrangeOptions(pdf, pageOrder, o)).output();
        });
    }

    // ------------------------------------------------------------------ TO-PDF

    /**
     * Convert each input to its own PDF at {@code imageSize}. Done manually (not via
     * {@link OperationRunner#runBatch}) so the requested page size is honoured for images,
     * while still applying the same data-safety naming (never overwrite a source).
     */
    public OperationRunner.BatchOutcome toPdf(List<Path> files, PageSize imageSize, Path outputDir)
            throws PdfOperationException {
        List<Path> outputs = new ArrayList<>();
        List<OperationRunner.BatchOutcome.Failure> failures = new ArrayList<>();
        int renamed = 0;
        for (Path f : files) {
            Path desired = outputDir.resolve(OperationRunner.outputName(OperationType.IMAGES_TO_PDF, f));
            Path out = OperationRunner.safeOutput(desired, files, OperationRunner.OverwritePolicy.RENAME);
            if (!out.equals(desired)) renamed++;
            try {
                DocumentConverter.toPdf(f, out, imageSize);
                outputs.add(out);
            } catch (PdfOperationException e) {
                failures.add(new OperationRunner.BatchOutcome.Failure(f.getFileName().toString(), e.getMessage()));
            }
        }
        return new OperationRunner.BatchOutcome(outputs, failures, renamed, files.size());
    }

    // ----------------------------------------------------------------- PROTECT

    public OperationRunner.BatchOutcome protect(List<Path> files, String userPassword,
                                                String ownerPassword, Path outputDir)
            throws PdfOperationException {
        return runBatch(OperationType.PROTECT, files, outputDir,
            (pdf, out) -> PdfProtector.execute(new ProtectOptions(pdf, userPassword, ownerPassword, out)));
    }

    // ------------------------------------------------------------------ UNLOCK

    public OperationRunner.BatchOutcome unlock(List<Path> files, String password, Path outputDir)
            throws PdfOperationException {
        return runBatch(OperationType.UNLOCK, files, outputDir,
            (pdf, out) -> PdfUnlocker.execute(new UnlockOptions(pdf, password, out)));
    }

    // ---------------------------------------------------------------- METADATA

    /** Read a PDF's document-info metadata (input may be office/image → converted first). */
    public PdfMetadata readMetadata(Path file, Path scratchOut)
            throws PdfOperationException, InvalidPageRangeException {
        return run(file, scratchOut, (pdf, o) -> PdfMetadataEditor.read(pdf));
    }

    /** Edit (or strip) a PDF's metadata; null field = unchanged, empty = cleared. */
    public Path editMetadata(Path file, String title, String author, String subject,
                             String keywords, boolean strip, Path output)
            throws PdfOperationException, InvalidPageRangeException {
        return run(file, output, (pdf, o) ->
            PdfMetadataEditor.execute(new MetadataOptions(pdf, title, author, subject, keywords, strip, o)).output());
    }

    // --------------------------------------------------------------- WATERMARK

    public OperationRunner.BatchOutcome watermark(List<Path> files, String text, Path image,
                                                  double opacity, double rotation, double scale, Path outputDir)
            throws PdfOperationException {
        return runBatch(OperationType.WATERMARK, files, outputDir,
            (pdf, out) -> PdfWatermarker.execute(
                new WatermarkOptions(pdf, text, image, opacity, rotation, scale, out)));
    }

    // ------------------------------------------------------------------ REDACT

    public Path redact(Path file, List<RedactRegion> regions, int dpi, Path output)
            throws PdfOperationException, InvalidPageRangeException {
        return run(file, output, (pdf, o) ->
            PdfRedactor.execute(new RedactOptions(pdf, regions, dpi, o)).output());
    }

    // --------------------------------------------------------------- TO-IMAGES

    /** Render selected pages of a single input to image files inside {@code outputDir}. */
    public List<Path> toImages(Path file, ImageFormat format, int dpi, String pagesExpr,
                               float jpegQuality, Path outputDir, String baseName)
            throws PdfOperationException, InvalidPageRangeException {
        PdfToImageResult result = run(file, outputDir, (pdf, dir) -> {
            PageRange range = parseRange(pagesExpr, pdf);
            return PdfToImageConverter.execute(
                new PdfToImageOptions(pdf, format, dpi, range, jpegQuality, dir, baseName));
        });
        return result.images();
    }

    // ---------------------------------------------------------------- TO-TEXT

    public Path toText(Path file, TextFormat format, String pagesExpr, Path outputDir, String baseName)
            throws PdfOperationException, InvalidPageRangeException {
        PdfToTextResult result = run(file, outputDir, (pdf, dir) -> {
            PageRange range = parseRange(pagesExpr, pdf);
            return PdfTextExporter.execute(new PdfToTextOptions(pdf, format, range, dir, baseName));
        });
        return result.output();
    }

    // --------------------------------------------------------------- internals

    /** {@code <stem><suffix>.pdf} for a MAP operation writing {@code type}. */
    public String outputName(OperationType type, Path input) {
        return OperationRunner.outputName(type, input);
    }

    private PageRange parseRange(String expr, Path pdf) throws PdfOperationException, InvalidPageRangeException {
        if (expr == null || expr.isBlank()) return PageRange.ALL;
        return PageRangeParser.parse(expr, Uploads.countPages(pdf));
    }

    /**
     * Runs a single-input MAP op, converting the raw input to PDF first (via core), and
     * surfacing the checked exceptions the web layer maps to HTTP status codes.
     */
    private <R> R run(Path rawInput, Path out, Execution<R> exec)
            throws PdfOperationException, InvalidPageRangeException {
        try {
            return OperationRunner.run(rawInput, out, exec);
        } catch (PdfOperationException | InvalidPageRangeException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(message(e), e);
        }
    }

    private OperationRunner.BatchOutcome runBatch(OperationType type, List<Path> files,
                                                  Path outputDir, Execution<?> exec)
            throws PdfOperationException {
        return OperationRunner.runBatch(type, files, outputDir, exec,
            ProgressSink.NONE, OperationRunner.OverwritePolicy.RENAME);
    }

    private static String message(Throwable t) {
        return (t.getMessage() != null && !t.getMessage().isBlank()) ? t.getMessage() : t.getClass().getSimpleName();
    }
}
