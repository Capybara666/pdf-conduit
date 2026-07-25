package com.pdfconduit.core.pipeline;

import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import com.pdfconduit.core.analyze.PiiFinding;
import com.pdfconduit.core.analyze.PiiRegion;
import com.pdfconduit.core.analyze.PiiScanResult;
import com.pdfconduit.core.analyze.PiiScanner;
import com.pdfconduit.core.pipeline.Document.DocType;
import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.*;
import com.pdfconduit.core.operations.PdfArranger;
import com.pdfconduit.core.operations.PdfCompressor;
import com.pdfconduit.core.operations.PdfCropper;
import com.pdfconduit.core.operations.PdfMerger;
import com.pdfconduit.core.operations.PdfMetadataEditor;
import com.pdfconduit.core.operations.PdfNupImposer;
import com.pdfconduit.core.operations.PdfPageMarker;
import com.pdfconduit.core.operations.PdfOcr;
import com.pdfconduit.core.operations.PdfProtector;
import com.pdfconduit.core.operations.PdfRedactor;
import com.pdfconduit.core.operations.PdfRepairer;
import com.pdfconduit.core.operations.PdfRotator;
import com.pdfconduit.core.operations.PdfSplitter;
import com.pdfconduit.core.operations.PdfTextExporter;
import com.pdfconduit.core.operations.PdfToImageConverter;
import com.pdfconduit.core.operations.PdfUnlocker;
import com.pdfconduit.core.operations.PdfWatermarker;
import com.pdfconduit.core.service.NamedBytes;
import com.pdfconduit.core.util.PageOrderParser;
import com.pdfconduit.core.util.PageRangeParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes a {@link PipelineModel}: validates it, orders nodes topologically and
 * runs each one, threading bundles of documents between nodes via temp files.
 * Terminal nodes write to their destination (a file for a single result, a
 * folder for several). JavaFX-free, so it can be unit-tested headlessly.
 */
public final class PipelineExecutor {

    private PipelineExecutor() {}

    /** Progress callback (node-level). */
    public interface Progress {
        void update(int done, int total, String message);
    }

    /** Saved outputs per terminal node id. */
    public record Result(Map<String, List<Path>> savedByNode) {}

    public static Result run(PipelineModel model, Progress progress) throws PipelineException {
        List<ValidationError> errors = PipelineValidator.validate(model);
        if (!errors.isEmpty()) {
            throw new PipelineException("Cannot run: " + errors.get(0).message());
        }

        List<PipelineNode> order;
        try {
            order = PipelineGraph.topologicalOrder(model);
        } catch (PipelineGraph.CycleException e) {
            throw new PipelineException("The pipeline contains a cycle.");
        }

        Map<String, List<Document>> outputs = new HashMap<>();
        Map<String, List<Path>> saved = new LinkedHashMap<>();
        Set<Path> temps = new HashSet<>();

        List<PipelineNode> opNodes = order.stream().filter(n -> !n.kind.isSource()).toList();
        int total = opNodes.size();
        int done = 0;

        try {
            for (PipelineNode n : order) {
                if (n.kind.isSource()) {
                    List<Document> docs = new ArrayList<>();
                    for (Path f : n.files) {
                        // Office/text documents can't be a PageSource, so convert them to
                        // PDF up front. Images stay images so reduce nodes can still place
                        // them at a chosen page size.
                        if (DocumentConverter.classify(f) == DocumentConverter.Kind.OFFICE) {
                            if (progress != null) {
                                progress.update(done, total, "Converting " + f.getFileName() + "…");
                            }
                            List<Path> created = new ArrayList<>();
                            Path pdf = DocumentConverter.ensurePdf(f, PageSize.FIT, created);
                            temps.addAll(created);
                            docs.add(new Document(pdf, DocType.PDF, Document.stemOf(f)));
                        } else {
                            docs.add(new Document(f, Document.typeOf(f), Document.stemOf(f)));
                        }
                    }
                    outputs.put(n.id, docs);
                    continue;
                }

                if (progress != null) progress.update(done, total, "Running " + n.kind.label + "…");

                List<Document> inputs = new ArrayList<>();
                for (Connection c : model.incoming(n.id)) {
                    inputs.addAll(outputs.getOrDefault(c.fromNodeId(), List.of()));
                }

                boolean terminal = model.isTerminal(n);
                List<Document> produced = n.kind.isReduce()
                    ? runReduce(n, inputs, terminal, temps)
                    : runMap(n, inputs, terminal, temps);

                outputs.put(n.id, produced);
                if (terminal) {
                    saved.put(n.id, produced.stream().map(Document::file).toList());
                }
                done++;
                if (progress != null) progress.update(done, total, "Finished " + n.kind.label);
            }
        } catch (PipelineException e) {
            throw e;
        } catch (Exception e) {
            throw new PipelineException(e.getMessage(), e);
        } finally {
            for (Path t : temps) {
                try { Files.deleteIfExists(t); } catch (IOException ignored) {}
            }
        }

        return new Result(saved);
    }

    // --- reduce ops: bundle -> single document ----------------------------

    private static List<Document> runReduce(PipelineNode n, List<Document> inputs,
                                            boolean terminal, Set<Path> temps)
            throws PipelineException {
        String baseName = inputs.isEmpty() ? n.kind.name().toLowerCase()
                                           : inputs.get(0).baseName() + n.kind.suffix();
        Path out = terminal ? destFile(n, baseName, false) : temp(temps);
        try {
            if (n.kind == NodeKind.MERGE) {
                // Collapse the bundle into one PDF (images kept at their natural size).
                PdfMerger.execute(new MergeOptions(toSources(inputs, PageSize.FIT), out));
            } else {
                throw new PipelineException("Not a reduce node: " + n.kind);
            }
        } catch (Exception e) {
            throw new PipelineException(n.kind.label + ": " + e.getMessage(), e);
        }
        return List.of(new Document(out, DocType.PDF, baseName));
    }

    /** Turns a bundle into merge sources: PDFs as page sources, images at {@code imageSize}. */
    private static List<PageSource> toSources(List<Document> inputs, PageSize imageSize) {
        List<PageSource> sources = new ArrayList<>();
        for (Document d : inputs) {
            sources.add(d.type() == DocType.PDF
                ? new PageSource.PdfPageSource(d.file(), PageRange.ALL)
                : new PageSource.ImageSource(d.file(), imageSize));
        }
        return sources;
    }

    // --- map ops: each input document -> one output document --------------

    private static List<Document> runMap(PipelineNode n, List<Document> inputs,
                                         boolean terminal, Set<Path> temps)
            throws PipelineException {
        List<Document> results = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        boolean multipleOutputs = inputs.size() > 1;
        for (Document in : inputs) {
            String baseName = in.baseName() + n.kind.suffix();

            // Extract in "separate files" mode emits one PDF per page. The validator
            // guarantees such a node is terminal, so we write straight to its folder
            // and add every produced file to the bundle.
            if (n.kind == NodeKind.EXTRACT && n.splitMode == SplitMode.SEPARATE) {
                try {
                    Path src = ensurePdf(in, temps);
                    SplitResult r = PdfSplitter.execute(
                        new SplitOptions(src, range(n.pages, src), SplitMode.SEPARATE, destDir(n)));
                    for (Path f : r.outputs()) results.add(new Document(f, DocType.PDF, baseName));
                } catch (PipelineException e) {
                    throw e;
                } catch (Exception e) {
                    throw new PipelineException(
                        n.kind.label + " (" + in.file().getFileName() + "): " + e.getMessage(), e);
                }
                continue;
            }

            // Export sinks write non-PDF files (images / text) into the node's folder.
            // The validator guarantees they are terminal.
            if (n.kind.isExport()) {
                try {
                    Path src = ensurePdf(in, temps);
                    if (n.kind == NodeKind.TO_IMAGES) {
                        PdfToImageResult r = PdfToImageConverter.execute(new PdfToImageOptions(
                            src, n.imageFormat, n.imageDpi, PageRange.ALL, n.jpegQuality,
                            destDir(n), in.baseName()));
                        for (Path f : r.images()) results.add(new Document(f, DocType.OTHER, baseName));
                    } else {   // TO_TEXT
                        PdfToTextResult r = PdfTextExporter.execute(new PdfToTextOptions(
                            src, n.textFormat, range(n.pages, src), destDir(n), in.baseName()));
                        results.add(new Document(r.output(), DocType.OTHER, baseName));
                    }
                } catch (PipelineException e) {
                    throw e;
                } catch (Exception e) {
                    throw new PipelineException(
                        n.kind.label + " (" + in.file().getFileName() + "): " + e.getMessage(), e);
                }
                continue;
            }

            Path out = terminal
                ? destFile(n, uniqueName(baseName, usedNames), multipleOutputs)
                : temp(temps);
            try {
                // TO PDF converts each input to its own PDF (no merge): PDFs pass
                // through, images are placed at the chosen page size.
                if (n.kind == NodeKind.IMAGES_TO_PDF) {
                    PageSource source = in.type() == DocType.PDF
                        ? new PageSource.PdfPageSource(in.file(), PageRange.ALL)
                        : new PageSource.ImageSource(in.file(), n.pageSize);
                    PdfMerger.execute(new MergeOptions(List.of(source), out));
                    results.add(new Document(out, DocType.PDF, baseName));
                    continue;
                }

                // Page operations need a PDF; convert images (and anything else) first.
                Path src = ensurePdf(in, temps);
                switch (n.kind) {
                    case EXTRACT -> PdfSplitter.execute(
                        new SplitOptions(src, range(n.pages, src), out));
                    case COMPRESS -> PdfCompressor.execute(
                        new CompressOptions(src, n.targetBytes, out));
                    case ROTATE -> PdfRotator.execute(
                        new RotateOptions(src, range(n.pages, src), n.angle, out));
                    case ARRANGE -> PdfArranger.execute(
                        new ArrangeOptions(src, order(n.order, src), out));
                    case PROTECT -> PdfProtector.execute(
                        new ProtectOptions(src, n.password, n.ownerPassword, out));
                    case UNLOCK -> PdfUnlocker.execute(
                        new UnlockOptions(src, n.password, out));
                    case METADATA -> PdfMetadataEditor.execute(new MetadataOptions(src,
                        blankToNull(n.metaTitle), blankToNull(n.metaAuthor),
                        blankToNull(n.metaSubject), blankToNull(n.metaKeywords), n.metaStrip, out));
                    case WATERMARK -> {
                        boolean useImage = n.wmImage != null && !n.wmImage.isBlank();
                        PdfWatermarker.execute(new WatermarkOptions(src,
                            useImage ? null : blankToNull(n.wmText),
                            useImage ? Path.of(n.wmImage) : null,
                            n.wmOpacity, n.wmRotation, n.wmScale, out));
                    }
                    case CROP -> PdfCropper.execute(new CropOptions(src,
                        n.cropTop, n.cropRight, n.cropBottom, n.cropLeft, n.cropMm, out));
                    case NUP -> PdfNupImposer.execute(
                        new NupOptions(src, n.nupLayout, n.nupBooklet, out));
                    case PAGE_MARKS -> PdfPageMarker.execute(new PageMarksOptions(src,
                        n.pmHeaderLeft, n.pmHeaderCenter, n.pmHeaderRight,
                        n.pmFooterLeft, n.pmFooterCenter, n.pmFooterRight,
                        (float) n.pmFontSize, (float) n.pmMargin, n.pmSkipFirst,
                        n.pmStartNumber, n.pmPrefix, out));
                    case OCR -> PdfOcr.execute(new OcrOptions(src, n.ocrLanguages, n.ocrDpi, out));
                    case GDPR_REDACT -> PdfRedactor.execute(
                        new RedactOptions(src, redactRegions(PiiScanner.scan(src)), 0, out));
                    // Repair sees the file as-is: a .pdf input is never re-encoded by ensurePdf,
                    // so the damaged byte structure reaches the repairer intact.
                    case REPAIR -> PdfRepairer.execute(new RepairOptions(src, out));
                    default -> throw new PipelineException("Not a map node: " + n.kind);
                }
            } catch (PipelineException e) {
                throw e;
            } catch (Exception e) {
                throw new PipelineException(
                    n.kind.label + " (" + in.file().getFileName() + "): " + e.getMessage(), e);
            }
            results.add(new Document(out, DocType.PDF, baseName));
        }
        return results;
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Destination path for a terminal node. A single output may be an explicit
     * {@code .pdf} file; otherwise (or whenever several outputs are produced) the
     * destination is treated as a folder and each result is named after its
     * input. When several outputs are expected but the destination still points
     * at a {@code .pdf} file, its parent folder is used — so a stale single-file
     * destination never collapses many results onto one file.
     */
    private static Path destFile(PipelineNode n, String baseName, boolean multipleOutputs)
            throws PipelineException {
        String dest = n.outputDestination;
        if (dest == null || dest.isBlank()) {
            throw new PipelineException(n.kind.label + " has no output destination.");
        }
        Path p = Path.of(dest);
        boolean looksLikeFile = dest.toLowerCase().endsWith(".pdf") && !Files.isDirectory(p);
        boolean asFolder = multipleOutputs || !looksLikeFile;
        try {
            if (asFolder) {
                Path dir = (looksLikeFile && p.getParent() != null) ? p.getParent() : p;
                Files.createDirectories(dir);
                return dir.resolve(baseName + ".pdf");
            }
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            return p;
        } catch (IOException e) {
            throw new PipelineException("Cannot create output location: " + e.getMessage(), e);
        }
    }

    private static String uniqueName(String base, Set<String> used) {
        String name = base;
        int i = 2;
        while (!used.add(name)) {
            name = base + "-" + i++;
        }
        return name;
    }

    /** Returns a PDF for {@code in}, converting (and tracking the temp) if needed. */
    private static Path ensurePdf(Document in, Set<Path> temps) throws Exception {
        if (in.type() == DocType.PDF) return in.file();
        List<Path> created = new ArrayList<>();
        Path src = DocumentConverter.ensurePdf(in.file(), PageSize.FIT, created);
        temps.addAll(created);
        return src;
    }

    /** The output folder of a terminal node (its destination, or that file's parent). */
    private static Path destDir(PipelineNode n) throws PipelineException {
        String dest = n.outputDestination;
        if (dest == null || dest.isBlank()) {
            throw new PipelineException(n.kind.label + " has no output destination.");
        }
        Path p = Path.of(dest);
        Path dir = (dest.toLowerCase().endsWith(".pdf") && !Files.isDirectory(p) && p.getParent() != null)
            ? p.getParent() : p;
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new PipelineException("Cannot create output location: " + e.getMessage(), e);
        }
    }

    private static Path temp(Set<Path> temps) throws PipelineException {
        try {
            Path t = Files.createTempFile("pipeline-", ".pdf");
            temps.add(t);
            return t;
        } catch (IOException e) {
            throw new PipelineException("Cannot create temp file: " + e.getMessage(), e);
        }
    }

    private static PageRange range(String expr, Path pdf) throws Exception {
        if (expr == null || expr.isBlank()) return PageRange.ALL;
        return PageRangeParser.parse(expr, pageCount(pdf));
    }

    private static List<Integer> order(String expr, Path pdf) throws Exception {
        return PageOrderParser.parse(expr, pageCount(pdf));
    }

    /**
     * Collects the on-page redaction rectangles from a PII scan — one per occurrence of every
     * concrete value finding. The scanner's regions already live in the redactor's coordinate
     * space, so this is a coordinate-compatible hand-off (mirrors the web /auto-redact endpoint).
     * Special-category keyword flags carry no regions, so nothing is blacked out for them.
     */
    private static List<RedactRegion> redactRegions(PiiScanResult scan) {
        List<RedactRegion> regions = new ArrayList<>();
        for (PiiFinding f : scan.findings()) {
            for (PiiRegion r : f.regions()) {
                regions.add(new RedactRegion(r.page(), r.x(), r.y(), r.width(), r.height()));
            }
        }
        return regions;
    }

    /** Blank → null (a metadata field left empty means "leave unchanged"). */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static int pageCount(Path pdf) throws IOException, PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    // =====================================================================
    // In-memory execution — no temp files, no host paths. Documents are
    // carried between nodes as byte[]. Reuses PipelineValidator / PipelineGraph
    // and the parsing helpers above; the per-node dispatch mirrors the disk
    // path's map/reduce structure but calls the operations' byte[] variants.
    // =====================================================================

    /** A document flowing through an in-memory run: its bytes, type, a name stem and an extension. */
    private record MemDoc(byte[] data, DocType type, String baseName, String ext) {}

    /**
     * Runs {@code model} entirely in memory. Source nodes' {@code files} are ignored; instead
     * {@code sourceResolver} supplies the uploaded bytes for each source node (PDF or image bytes;
     * office uploads must be pre-converted to PDF bytes by the caller). Returns, per terminal node
     * id, the produced outputs as {@link NamedBytes} (bytes + suggested file name). No disk is
     * touched except the office/DOCX exceptions documented on the operations themselves.
     */
    public static Map<String, List<NamedBytes>> runInMemory(
            PipelineModel model,
            java.util.function.Function<PipelineNode, List<byte[]>> sourceResolver,
            Progress progress) throws PipelineException {
        return runInMemory(model, sourceResolver, Map.of(), progress);
    }

    /**
     * Wraps a runtime exception thrown by a {@link PipelineGuard} check so the executor's blanket
     * "wrap anything into a PipelineException" handling passes it through untouched — a host's typed
     * rejection (e.g. "OCR disabled") must reach the caller as itself, not as an operation failure.
     */
    private static final class GuardFailure extends RuntimeException {
        GuardFailure(RuntimeException cause) { super(cause); }
        RuntimeException cause() { return (RuntimeException) getCause(); }
    }

    private static void guardDocument(PipelineGuard guard, byte[] pdf) throws PdfOperationException {
        try { guard.checkDocument(pdf); } catch (RuntimeException e) { throw new GuardFailure(e); }
    }

    private static void guardRender(PipelineGuard guard, byte[] pdf, int dpi)
            throws PdfOperationException {
        try { guard.checkRender(pdf, dpi); } catch (RuntimeException e) { throw new GuardFailure(e); }
    }

    private static void guardOcr(PipelineGuard guard) throws PdfOperationException {
        try { guard.checkOcrAllowed(); } catch (RuntimeException e) { throw new GuardFailure(e); }
    }

    /**
     * As {@link #runInMemory(PipelineModel, java.util.function.Function, Progress)}, but with a
     * {@code nodeImages} map supplying per-node uploaded asset bytes (keyed by node id). This is how
     * a WATERMARK node performs an <em>image</em> watermark in memory: the node's {@code wmImage}
     * carries only a name reference (no host path), and the caller passes the actual image bytes
     * here keyed by that node's id. The Path-based {@link #run} is unaffected — it still resolves
     * {@code wmImage} as a host file path.
     */
    public static Map<String, List<NamedBytes>> runInMemory(
            PipelineModel model,
            java.util.function.Function<PipelineNode, List<byte[]>> sourceResolver,
            Map<String, byte[]> nodeImages,
            Progress progress) throws PipelineException {
        return runInMemory(model, sourceResolver, nodeImages, PipelineGuard.NONE, progress);
    }

    /**
     * As {@link #runInMemory(PipelineModel, java.util.function.Function, Map, Progress)}, but with a
     * {@link PipelineGuard} applying the host's per-request ceilings (page count, render DPI/pixel
     * area, OCR availability + concurrency) to the nodes as they run. A multi-tenant host (the web
     * backend) passes its own guard; desktop/CLI callers use the overloads above and get
     * {@link PipelineGuard#NONE}, so their behaviour — and cost — is unchanged.
     */
    public static Map<String, List<NamedBytes>> runInMemory(
            PipelineModel model,
            java.util.function.Function<PipelineNode, List<byte[]>> sourceResolver,
            Map<String, byte[]> nodeImages,
            PipelineGuard guard,
            Progress progress) throws PipelineException {
        try {
            return runMemory(model, sourceResolver, nodeImages,
                guard == null ? PipelineGuard.NONE : guard, progress);
        } catch (GuardFailure f) {
            throw f.cause();
        }
    }

    private static Map<String, List<NamedBytes>> runMemory(
            PipelineModel model,
            java.util.function.Function<PipelineNode, List<byte[]>> sourceResolver,
            Map<String, byte[]> nodeImages,
            PipelineGuard guard,
            Progress progress) throws PipelineException {

        Map<String, byte[]> images = nodeImages == null ? Map.of() : nodeImages;
        List<ValidationError> errors = PipelineValidator.validateInMemory(model);
        if (!errors.isEmpty()) {
            throw new PipelineException("Cannot run: " + errors.get(0).message());
        }

        List<PipelineNode> order;
        try {
            order = PipelineGraph.topologicalOrder(model);
        } catch (PipelineGraph.CycleException e) {
            throw new PipelineException("The pipeline contains a cycle.");
        }

        Map<String, List<MemDoc>> outputs = new HashMap<>();
        Map<String, List<NamedBytes>> terminalOutputs = new LinkedHashMap<>();

        int total = (int) order.stream().filter(n -> !n.kind.isSource()).count();
        int done = 0;

        for (PipelineNode n : order) {
            if (n.kind.isSource()) {
                outputs.put(n.id, sourceDocs(n, sourceResolver, guard));
                continue;
            }

            if (progress != null) progress.update(done, total, "Running " + n.kind.label + "…");

            List<MemDoc> inputs = new ArrayList<>();
            for (Connection c : model.incoming(n.id)) {
                inputs.addAll(outputs.getOrDefault(c.fromNodeId(), List.of()));
            }

            List<MemDoc> produced;
            try {
                produced = n.kind.isReduce()
                    ? runReduceMem(n, inputs, guard)
                    : runMapMem(n, inputs, images, guard);
            } catch (PipelineException | GuardFailure e) {
                throw e;
            } catch (Exception e) {
                throw new PipelineException(n.kind.label + ": " + e.getMessage(), e);
            }

            outputs.put(n.id, produced);
            if (model.isTerminal(n)) {
                terminalOutputs.put(n.id, name(produced));
            }
            done++;
            if (progress != null) progress.update(done, total, "Finished " + n.kind.label);
        }
        return terminalOutputs;
    }

    /** Reads a source node's uploaded bytes into MemDocs, detecting PDF vs image by magic bytes. */
    private static List<MemDoc> sourceDocs(PipelineNode n,
                                           java.util.function.Function<PipelineNode, List<byte[]>> resolver,
                                           PipelineGuard guard)
            throws PipelineException {
        List<byte[]> raw = resolver.apply(n);
        if (raw == null) raw = List.of();
        List<MemDoc> docs = new ArrayList<>(raw.size());
        int i = 1;
        for (byte[] b : raw) {
            String baseName = "file" + i++;
            if (com.pdfconduit.core.util.FileTypeDetector.isPdf(b)) {
                // Host ceilings (e.g. the web PDF-bomb page cap) apply to everything entering here.
                try {
                    guardDocument(guard, b);
                } catch (PdfOperationException e) {
                    throw new PipelineException("Source " + baseName + ": " + e.getMessage(), e);
                }
                docs.add(new MemDoc(b, DocType.PDF, baseName, "pdf"));
            } else if (com.pdfconduit.core.util.FileTypeDetector.isSupportedImage(b)) {
                docs.add(new MemDoc(b, DocType.IMAGE, baseName, "img"));
            } else {
                throw new PipelineException(
                    "Source " + baseName + ": unsupported data (expected a PDF or image; "
                    + "convert office documents to PDF before uploading).");
            }
        }
        return docs;
    }

    /** Names a node's produced outputs, disambiguating duplicate stems. */
    private static List<NamedBytes> name(List<MemDoc> docs) {
        List<NamedBytes> named = new ArrayList<>(docs.size());
        Set<String> used = new HashSet<>();
        for (MemDoc d : docs) {
            named.add(new NamedBytes(uniqueName(d.baseName(), used) + "." + d.ext(), d.data()));
        }
        return named;
    }

    // --- in-memory reduce -------------------------------------------------

    private static List<MemDoc> runReduceMem(PipelineNode n, List<MemDoc> inputs, PipelineGuard guard)
            throws Exception {
        if (n.kind != NodeKind.MERGE) throw new PipelineException("Not a reduce node: " + n.kind);
        List<byte[]> pdfs = new ArrayList<>(inputs.size());
        for (MemDoc in : inputs) pdfs.add(ensurePdfBytes(in, PageSize.FIT));
        byte[] merged = PdfMerger.executeBytes(pdfs);
        // Merge is the one page-count amplifier: N guarded inputs can still exceed the ceiling once
        // combined, so the result is re-checked (mirrors the single-operation /api/merge behaviour).
        guardDocument(guard, merged);
        String baseName = inputs.isEmpty()
            ? n.kind.name().toLowerCase() : inputs.get(0).baseName() + n.kind.suffix();
        return List.of(new MemDoc(merged, DocType.PDF, baseName, "pdf"));
    }

    // --- in-memory map ----------------------------------------------------

    private static List<MemDoc> runMapMem(PipelineNode n, List<MemDoc> inputs,
                                          Map<String, byte[]> nodeImages,
                                          PipelineGuard guard) throws Exception {
        List<MemDoc> results = new ArrayList<>();
        for (MemDoc in : inputs) {
            String baseName = in.baseName() + n.kind.suffix();

            // Extract in "separate" mode: one PDF per page (validator guarantees terminal).
            if (n.kind == NodeKind.EXTRACT && n.splitMode == SplitMode.SEPARATE) {
                byte[] pdf = ensurePdfBytes(in, PageSize.FIT);
                List<byte[]> pages = PdfSplitter.separateBytes(pdf, rangeBytes(n.pages, pdf));
                int width = Integer.toString(Math.max(1, pages.size())).length();
                for (int i = 0; i < pages.size(); i++) {
                    results.add(new MemDoc(pages.get(i), DocType.PDF,
                        baseName + "_p" + pad(i + 1, width), "pdf"));
                }
                continue;
            }

            // Export sinks: non-PDF outputs (validator guarantees terminal).
            if (n.kind.isExport()) {
                byte[] pdf = ensurePdfBytes(in, PageSize.FIT);
                if (n.kind == NodeKind.TO_IMAGES) {
                    // The node's DPI is client-supplied: gate it (and the resulting pixel area)
                    // exactly as the single-operation image endpoint does, BEFORE rasterising.
                    guardRender(guard, pdf, n.imageDpi);
                    List<byte[]> images = PdfToImageConverter.executeBytes(
                        pdf, n.imageFormat, n.imageDpi, PageRange.ALL, n.jpegQuality);
                    int width = Integer.toString(Math.max(1, images.size())).length();
                    for (int i = 0; i < images.size(); i++) {
                        results.add(new MemDoc(images.get(i), DocType.OTHER,
                            baseName + "_p" + pad(i + 1, width), n.imageFormat.extension()));
                    }
                } else {   // TO_TEXT
                    byte[] text = PdfTextExporter.toTextBytes(pdf, n.textFormat, rangeBytes(n.pages, pdf));
                    results.add(new MemDoc(text, DocType.OTHER, baseName, n.textFormat.extension()));
                }
                continue;
            }

            // TO PDF: each input becomes its own PDF (image placed at the chosen page size).
            if (n.kind == NodeKind.IMAGES_TO_PDF) {
                byte[] pdf = in.type() == DocType.PDF
                    ? in.data()
                    : com.pdfconduit.core.operations.ImageToPdfConverter.executeBytes(
                        List.of(in.data()), n.pageSize);
                results.add(new MemDoc(pdf, DocType.PDF, baseName, "pdf"));
                continue;
            }

            // Page operations need a PDF; convert images first.
            byte[] pdf = ensurePdfBytes(in, PageSize.FIT);
            byte[] out = switch (n.kind) {
                case EXTRACT   -> PdfSplitter.combineBytes(pdf, rangeBytes(n.pages, pdf));
                case COMPRESS  -> PdfCompressor.compressBytes(pdf, n.targetBytes).bytes();
                case ROTATE    -> PdfRotator.executeBytes(pdf, rangeBytes(n.pages, pdf), n.angle);
                case ARRANGE   -> PdfArranger.executeBytes(pdf, orderBytes(n.order, pdf));
                case PROTECT   -> PdfProtector.executeBytes(pdf, n.password, n.ownerPassword);
                // The upload was encrypted, so the page-count guard could not see inside it —
                // re-check the decrypted result (mirrors the single-operation /api/unlock).
                case UNLOCK    -> guarded(guard, PdfUnlocker.executeBytes(pdf, n.password));
                case METADATA  -> PdfMetadataEditor.executeBytes(pdf,
                    blankToNull(n.metaTitle), blankToNull(n.metaAuthor),
                    blankToNull(n.metaSubject), blankToNull(n.metaKeywords), n.metaStrip);
                case WATERMARK -> watermarkBytes(n, pdf, nodeImages);
                case CROP      -> PdfCropper.executeBytes(pdf,
                    n.cropTop, n.cropRight, n.cropBottom, n.cropLeft, n.cropMm);
                case NUP       -> PdfNupImposer.executeBytes(pdf, n.nupLayout, n.nupBooklet);
                case PAGE_MARKS -> PdfPageMarker.executeBytes(pdf,
                    n.pmHeaderLeft, n.pmHeaderCenter, n.pmHeaderRight,
                    n.pmFooterLeft, n.pmFooterCenter, n.pmFooterRight,
                    (float) n.pmFontSize, (float) n.pmMargin, n.pmSkipFirst,
                    n.pmStartNumber, n.pmPrefix);
                case OCR       -> ocrBytes(n, pdf, guard);
                // Scan for PII and feed every detected value's region straight into the redactor —
                // the same one-click scan→auto-redact hand-off exposed by the web /auto-redact endpoint.
                case GDPR_REDACT -> {
                    // Redaction rasterises the affected pages, so it is render-guarded too.
                    guardRender(guard, pdf, PdfRedactor.DEFAULT_DPI);
                    yield PdfRedactor.executeBytes(
                        pdf, redactRegions(PiiScanner.scanBytes(pdf)), 0).data();
                }
                // Repair sees the bytes as-is (a PDF MemDoc is passed through unchanged by
                // ensurePdfBytes) — the damage it repairs lives in the byte structure. It neither
                // rasterises nor amplifies the page count, so the source guard already covers it.
                case REPAIR    -> PdfRepairer.executeBytes(pdf).bytes();
                default -> throw new PipelineException("Not a map node: " + n.kind);
            };
            results.add(new MemDoc(out, DocType.PDF, baseName, "pdf"));
        }
        return results;
    }

    /** Applies the host's document ceiling to a node's result and returns it unchanged. */
    private static byte[] guarded(PipelineGuard guard, byte[] pdf) throws PdfOperationException {
        guardDocument(guard, pdf);
        return pdf;
    }

    /**
     * OCR in memory. Unlike every other node this shells out to an external {@code tesseract} per
     * page after rendering it, so it is gated three ways by the host: availability (a host may have
     * OCR switched off entirely), the render ceiling for the client-supplied {@code ocrDpi}, and the
     * host's own concurrency/timeout wrapper around the actual work.
     */
    private static byte[] ocrBytes(PipelineNode n, byte[] pdf, PipelineGuard guard)
            throws PdfOperationException {
        guardOcr(guard);
        int dpi = n.ocrDpi > 0 ? n.ocrDpi : PdfOcr.DEFAULT_DPI;
        guardRender(guard, pdf, dpi);
        try {
            return guard.runOcr(() -> PdfOcr.executeBytes(pdf, n.ocrLanguages, n.ocrDpi));
        } catch (RuntimeException e) {
            throw new GuardFailure(e);
        }
    }

    /**
     * Watermark in memory. An image watermark carries only a name reference in {@code wmImage};
     * its bytes are supplied out-of-band via {@code nodeImages} keyed by the node id (uploaded
     * alongside the pipeline). Falls back to a text watermark when no image is referenced.
     */
    private static byte[] watermarkBytes(PipelineNode n, byte[] pdf, Map<String, byte[]> nodeImages)
            throws Exception {
        boolean useImage = n.wmImage != null && !n.wmImage.isBlank();
        if (useImage) {
            byte[] image = nodeImages == null ? null : nodeImages.get(n.id);
            if (image == null) {
                throw new PipelineException(
                    "Watermark image for node '" + n.id + "' (" + n.wmImage + ") was not uploaded.");
            }
            return PdfWatermarker.executeBytes(pdf, null, image,
                n.wmOpacity, n.wmRotation, n.wmScale);
        }
        return PdfWatermarker.executeBytes(pdf, blankToNull(n.wmText), null,
            n.wmOpacity, n.wmRotation, n.wmScale);
    }

    /** PDF bytes for a MemDoc: passthrough for PDFs, in-memory Image-to-PDF for images. */
    private static byte[] ensurePdfBytes(MemDoc in, PageSize size) throws PdfOperationException {
        if (in.type() == DocType.PDF) return in.data();
        return com.pdfconduit.core.operations.ImageToPdfConverter.executeBytes(List.of(in.data()), size);
    }

    private static PageRange rangeBytes(String expr, byte[] pdf) throws Exception {
        if (expr == null || expr.isBlank()) return PageRange.ALL;
        return PageRangeParser.parse(expr, pageCountBytes(pdf));
    }

    private static List<Integer> orderBytes(String expr, byte[] pdf) throws Exception {
        return PageOrderParser.parse(expr, pageCountBytes(pdf));
    }

    private static int pageCountBytes(byte[] pdf) throws IOException, PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    private static String pad(int n, int width) {
        return String.format("%0" + Math.max(1, width) + "d", n);
    }
}
