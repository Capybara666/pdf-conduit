package org.example.app.pipeline;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.example.app.pipeline.Document.DocType;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.*;
import org.example.core.operations.PdfArranger;
import org.example.core.operations.PdfCompressor;
import org.example.core.operations.PdfMerger;
import org.example.core.operations.PdfMetadataEditor;
import org.example.core.operations.PdfProtector;
import org.example.core.operations.PdfRotator;
import org.example.core.operations.PdfSplitter;
import org.example.core.operations.PdfUnlocker;
import org.example.core.operations.PdfWatermarker;
import org.example.core.util.PageOrderParser;
import org.example.core.util.PageRangeParser;

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
                                           : inputs.get(0).baseName() + n.kind.suffix;
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
            String baseName = in.baseName() + n.kind.suffix;

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

    /** Blank → null (a metadata field left empty means "leave unchanged"). */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static int pageCount(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        }
    }
}
