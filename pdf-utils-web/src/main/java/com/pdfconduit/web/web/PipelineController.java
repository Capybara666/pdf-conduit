package com.pdfconduit.web.web;

import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.pipeline.NodeKind;
import com.pdfconduit.core.pipeline.PipelineException;
import com.pdfconduit.core.pipeline.PipelineExecutor;
import com.pdfconduit.core.pipeline.PipelineModel;
import com.pdfconduit.core.pipeline.PipelineNode;
import com.pdfconduit.core.pipeline.PipelineValidator;
import com.pdfconduit.core.service.NamedBytes;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.dto.NodeKindInfo;
import com.pdfconduit.web.dto.ValidationErrorDto;
import com.pdfconduit.web.guard.LoadGuard;
import com.pdfconduit.web.guard.OutputBudget;
import com.pdfconduit.web.guard.PipelineLimitsGuard;
import com.pdfconduit.web.support.PipelineJson;
import com.pdfconduit.web.support.Responses;
import com.pdfconduit.web.support.Uploads;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The visual-pipeline endpoints, run entirely in memory over the core
 * {@link PipelineExecutor#runInMemory}. Source nodes carry no host paths — their {@code files}
 * entries are treated as <em>names</em> matched (by basename) against the uploaded {@code files}
 * parts. Office uploads are converted to PDF bytes up front (the documented disk exception,
 * gated by {@code pdfconduit.web.office.enabled}); PDFs and images flow through as-is.
 */
@RestController
@RequestMapping("/api/pipeline")
public class PipelineController {

    private final Uploads uploads;
    private final LoadGuard loadGuard;
    private final PipelineLimitsGuard limits;
    private final OutputBudget outputBudget;

    private final int maxNodes;
    private final int maxConnections;
    private final int maxSourceDocuments;

    public PipelineController(Uploads uploads, LoadGuard loadGuard, PipelineLimitsGuard limits,
                              OutputBudget outputBudget, WebProperties props) {
        this.uploads = uploads;
        this.loadGuard = loadGuard;
        this.limits = limits;
        this.outputBudget = outputBudget;
        this.maxNodes = props.pipeline().maxNodes();
        this.maxConnections = props.pipeline().maxConnections();
        // Deliberately the SAME ceiling that bounds a normal batch upload: a pipeline source list is
        // just another way of naming the files one request will process.
        this.maxSourceDocuments = props.maxFilesPerRequest();
    }

    /**
     * Runs a pipeline and returns a ZIP of every terminal node's outputs.
     *
     * <p><b>Source→upload mapping:</b> each source node lists file names in its {@code files}
     * field; the executor is fed, per source node, the uploaded {@code files} parts whose original
     * filename (basename) equals those names, in the node's declared order.
     *
     * <p><b>Watermark image assets:</b> a WATERMARK node doing an <em>image</em> watermark carries
     * only a name reference in its {@code wmImage} field (no host path). The image bytes ride along
     * as separate {@code nodeAssets} parts, matched by basename to that {@code wmImage} name, and are
     * passed into the executor keyed by node id.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, path = "/run")
    public ResponseEntity<byte[]> run(@RequestParam("pipeline") String pipeline,
                                      @RequestParam(value = "files", required = false) List<MultipartFile> files,
                                      @RequestParam(value = "nodeAssets", required = false) List<MultipartFile> nodeAssets)
            throws IOException, PdfOperationException, PipelineException, InvalidPageRangeException {
        PipelineModel model = PipelineJson.parse(pipeline);
        guardGraphSize(model);

        // Index uploads by basename (Uploads.read gates office uploads when office is disabled).
        // NB: the parts are only an index here — what this request will actually cost is measured
        // below, per RESOLVED source reference, because the same part may be named many times.
        Map<String, NamedBytes> byName = new HashMap<>();
        long uploadBytes = 0;
        if (files != null) {
            for (MultipartFile f : files) {
                NamedBytes nb = uploads.read(f);
                byName.put(nb.filename(), nb);
            }
        }

        // Index watermark-image assets by basename (raw bytes; never routed through office conversion).
        Map<String, byte[]> assetsByName = new HashMap<>();
        if (nodeAssets != null) {
            for (MultipartFile f : nodeAssets) {
                byte[] data = f.getBytes();
                assetsByName.put(Uploads.filename(f), data);
                uploadBytes += data.length;
            }
        }

        // Resolve each source node's bytes eagerly (so checked conversion errors surface cleanly).
        Map<String, List<byte[]>> resolved = new HashMap<>();
        Map<String, byte[]> nodeImages = new HashMap<>();
        // Source bytes per upload name: a name resolved twice is the SAME bytes, so an office
        // upload referenced N times is converted once, not N times.
        Map<String, byte[]> sourceBytes = new HashMap<>();
        int sourceDocuments = 0;
        for (PipelineNode n : model.nodes) {
            if (n.kind == null) {
                throw new IllegalArgumentException("Pipeline node '" + n.id + "' has no kind.");
            }
            if (n.kind == NodeKind.WATERMARK && n.wmImage != null && !n.wmImage.isBlank()) {
                String key = name(n.wmImage, "watermark image", n.id);
                byte[] image = assetsByName.get(key);
                if (image == null) {
                    throw new IllegalArgumentException(
                        "No uploaded image matches watermark node '" + n.id + "' (" + key + ").");
                }
                nodeImages.put(n.id, image);
            }
            if (!n.kind.isSource()) continue;
            List<byte[]> bytes = new ArrayList<>(n.files.size());
            for (Path f : n.files) {
                String key = name(f == null ? null : f.toString(), "source file", n.id);
                NamedBytes nb = byName.get(key);
                if (nb == null) {
                    throw new IllegalArgumentException(
                        "No uploaded file matches pipeline source '" + key + "'.");
                }
                // Count the REFERENCE, not the part: every reference is one more document the run
                // will process, whether or not it names an upload already listed elsewhere.
                if (++sourceDocuments > maxSourceDocuments) {
                    throw new IllegalArgumentException(
                        "Pipeline resolves too many source documents (limit " + maxSourceDocuments
                        + " per request). A file listed more than once counts each time.");
                }
                byte[] data = sourceBytes.get(key);
                if (data == null) {
                    data = toSourceBytes(nb);
                    sourceBytes.put(key, data);
                }
                bytes.add(data);
                // Reserve against what is really processed, so duplicating one upload cannot make
                // the load guard's in-flight-byte ceiling underestimate the run by that factor.
                uploadBytes += data.length;
            }
            resolved.put(n.id, bytes);
        }

        // Heavy work: bound concurrency / in-flight bytes / runtime via the load guard, and apply the
        // per-request ceilings (page count, render DPI/pixels, OCR availability + permit) INSIDE the
        // run via PipelineLimitsGuard — the nodes' DPI/OCR settings are client-supplied, so without
        // it this endpoint would reach the same core operations with every ceiling skipped.
        Map<String, List<NamedBytes>> terminals = loadGuard.execute(uploadBytes,
            () -> PipelineExecutor.runInMemory(
                model, node -> resolved.getOrDefault(node.id, List.of()), nodeImages, limits, null));

        List<NamedBytes> all = new ArrayList<>();
        for (List<NamedBytes> nodeOutputs : terminals.values()) all.addAll(nodeOutputs);
        // Zipping copies the whole result set again (buffer + toByteArray), so refuse a result that
        // is already over the per-request budget rather than tripling it in the heap first. The
        // pipeline executor accumulates its terminals internally, so this is the last chokepoint.
        outputBudget.checkResultBytes(all);
        return Responses.zip(all, "pipeline_results.zip");
    }

    /**
     * Validates a pipeline in-memory; returns the list of problems (empty ⇒ valid), 200 — except for
     * a graph over the size ceiling, which is a 400 here too so the builder learns it before
     * uploading anything, and so /validate can never be used to pre-flight a graph /run would refuse.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, path = "/validate")
    public List<ValidationErrorDto> validate(@RequestParam("pipeline") String pipeline) {
        PipelineModel model = PipelineJson.parse(pipeline);
        guardGraphSize(model);
        return PipelineValidator.validateInMemory(model).stream().map(ValidationErrorDto::of).toList();
    }

    /**
     * Bounds the size of a client-supplied graph (→ 400). Every node is a full core operation and
     * the entire graph runs under a <em>single</em> {@link LoadGuard} permit with one processing
     * timeout, and timed-out work is not actually aborted — so an unbounded node count is an
     * unbounded amount of work bought by one request. The ceilings are far above any pipeline a
     * person builds in the visual editor.
     */
    private void guardGraphSize(PipelineModel model) {
        int nodes = model.nodes == null ? 0 : model.nodes.size();
        int connections = model.connections == null ? 0 : model.connections.size();
        if (nodes > maxNodes) {
            throw new IllegalArgumentException(
                "Pipeline has too many nodes: " + nodes + " (limit " + maxNodes + ").");
        }
        if (connections > maxConnections) {
            throw new IllegalArgumentException(
                "Pipeline has too many connections: " + connections
                + " (limit " + maxConnections + ").");
        }
    }

    /** The node-kind catalog for the builder palette. */
    @GetMapping("/kinds")
    public List<NodeKindInfo> kinds() {
        return Arrays.stream(NodeKind.values()).map(NodeKindInfo::of).toList();
    }

    /**
     * The upload name a client-supplied pipeline reference points at — its trailing name component,
     * never a host path. Every one of these strings comes verbatim from the request's pipeline JSON,
     * so a crafted value such as {@code "/"} or {@code ""} must be a clean 400, not an NPE —
     * {@code Path.of("/").getFileName()} is {@code null} — surfacing as a 500 plus a stack trace.
     */
    private static String name(String reference, String what, String nodeId) {
        String key = Uploads.basename(reference);
        if (key.isEmpty()) {
            throw new IllegalArgumentException(
                "Pipeline node '" + nodeId + "' has an unusable " + what + " name: '"
                + (reference == null ? "" : reference) + "'.");
        }
        return key;
    }

    /** Office uploads → PDF bytes (gated + temp-dir exception); PDFs/images pass through raw. */
    private byte[] toSourceBytes(NamedBytes nb) throws PdfOperationException {
        if (DocumentConverter.classify(Path.of(nb.filename())) == DocumentConverter.Kind.OFFICE) {
            return uploads.toPdf(nb);
        }
        return nb.data();
    }
}
