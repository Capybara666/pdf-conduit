package org.example.app.pipeline;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.example.app.pipeline.Document.DocType;
import org.example.core.model.*;
import org.example.core.operations.ImageToPdfConverter;
import org.example.core.operations.PdfCompressor;
import org.example.core.operations.PdfMerger;
import org.example.core.operations.PdfRotator;
import org.example.core.operations.PdfSplitter;
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
                        docs.add(new Document(f, Document.typeOf(f), Document.stemOf(f)));
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
        Path out = terminal ? destFile(n, baseName) : temp(temps);
        try {
            switch (n.kind) {
                case MERGE -> {
                    List<PageSource> sources = new ArrayList<>();
                    for (Document d : inputs) {
                        sources.add(d.type() == DocType.PDF
                            ? new PageSource.PdfPageSource(d.file(), PageRange.ALL)
                            : new PageSource.ImageSource(d.file(), PageSize.FIT));
                    }
                    PdfMerger.execute(new MergeOptions(sources, out));
                }
                case IMAGES_TO_PDF -> {
                    List<Path> images = inputs.stream().map(Document::file).toList();
                    ImageToPdfConverter.execute(new ImageToPdfOptions(images, n.pageSize, out));
                }
                default -> throw new PipelineException("Not a reduce node: " + n.kind);
            }
        } catch (Exception e) {
            throw new PipelineException(n.kind.label + ": " + e.getMessage(), e);
        }
        return List.of(new Document(out, DocType.PDF, baseName));
    }

    // --- map ops: each input document -> one output document --------------

    private static List<Document> runMap(PipelineNode n, List<Document> inputs,
                                         boolean terminal, Set<Path> temps)
            throws PipelineException {
        List<Document> results = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        for (Document in : inputs) {
            String baseName = in.baseName() + n.kind.suffix;
            Path out = terminal ? destFile(n, uniqueName(baseName, usedNames)) : temp(temps);
            try {
                switch (n.kind) {
                    case EXTRACT -> PdfSplitter.execute(
                        new SplitOptions(in.file(), range(n.pages, in.file()), out));
                    case COMPRESS -> PdfCompressor.execute(
                        new CompressOptions(in.file(), n.targetBytes, out));
                    case ROTATE -> PdfRotator.execute(
                        new RotateOptions(in.file(), range(n.pages, in.file()), n.angle, out));
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
     * Destination path for a terminal node. The GUI sets the destination to a
     * file when a single output is expected and to a folder when several are; we
     * disambiguate by whether the path is/looks like a directory.
     */
    private static Path destFile(PipelineNode n, String baseName) throws PipelineException {
        String dest = n.outputDestination;
        if (dest == null || dest.isBlank()) {
            throw new PipelineException(n.kind.label + " has no output destination.");
        }
        Path p = Path.of(dest);
        boolean folder = Files.isDirectory(p) || !dest.toLowerCase().endsWith(".pdf");
        try {
            if (folder) {
                Files.createDirectories(p);
                return p.resolve(baseName + ".pdf");
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
        int total;
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            total = doc.getNumberOfPages();
        }
        return PageRangeParser.parse(expr, total);
    }
}
