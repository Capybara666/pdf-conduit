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
import com.pdfconduit.web.dto.NodeKindInfo;
import com.pdfconduit.web.dto.ValidationErrorDto;
import com.pdfconduit.web.guard.LoadGuard;
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

    public PipelineController(Uploads uploads, LoadGuard loadGuard, PipelineLimitsGuard limits) {
        this.uploads = uploads;
        this.loadGuard = loadGuard;
        this.limits = limits;
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

        // Index uploads by basename (Uploads.read gates office uploads when office is disabled).
        Map<String, NamedBytes> byName = new HashMap<>();
        long uploadBytes = 0;
        if (files != null) {
            for (MultipartFile f : files) {
                NamedBytes nb = uploads.read(f);
                byName.put(nb.filename(), nb);
                uploadBytes += nb.data().length;
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
                bytes.add(toSourceBytes(nb));
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
        return Responses.zip(all, "pipeline_results.zip");
    }

    /** Validates a pipeline in-memory; returns the list of problems (empty ⇒ valid), always 200. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, path = "/validate")
    public List<ValidationErrorDto> validate(@RequestParam("pipeline") String pipeline) {
        PipelineModel model = PipelineJson.parse(pipeline);
        return PipelineValidator.validateInMemory(model).stream().map(ValidationErrorDto::of).toList();
    }

    /** The node-kind catalog for the builder palette. */
    @GetMapping("/kinds")
    public List<NodeKindInfo> kinds() {
        return Arrays.stream(NodeKind.values()).map(NodeKindInfo::of).toList();
    }

    /**
     * The upload name a client-supplied pipeline reference points at — its trailing name component,
     * never a host path. Every one of these strings comes verbatim from the request's pipeline JSON,
     * so a crafted value such as {@code "/"} or {@code ""} must be a clean 400, not an NPE (which
     * {@code Path.of("/").getFileName()} used to produce) surfacing as a 500 plus a stack trace.
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
