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

    public PipelineController(Uploads uploads, LoadGuard loadGuard) {
        this.uploads = uploads;
        this.loadGuard = loadGuard;
    }

    /**
     * Runs a pipeline and returns a ZIP of every terminal node's outputs.
     *
     * <p><b>Source→upload mapping:</b> each source node lists file names in its {@code files}
     * field; the executor is fed, per source node, the uploaded {@code files} parts whose original
     * filename (basename) equals those names, in the node's declared order.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, path = "/run")
    public ResponseEntity<byte[]> run(@RequestParam("pipeline") String pipeline,
                                      @RequestParam(value = "files", required = false) List<MultipartFile> files)
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

        // Resolve each source node's bytes eagerly (so checked conversion errors surface cleanly).
        Map<String, List<byte[]>> resolved = new HashMap<>();
        for (PipelineNode n : model.nodes) {
            if (!n.kind.isSource()) continue;
            List<byte[]> bytes = new ArrayList<>(n.files.size());
            for (Path f : n.files) {
                String key = f.getFileName().toString();
                NamedBytes nb = byName.get(key);
                if (nb == null) {
                    throw new IllegalArgumentException(
                        "No uploaded file matches pipeline source '" + key + "'.");
                }
                bytes.add(toSourceBytes(nb));
            }
            resolved.put(n.id, bytes);
        }

        // Heavy work: bound concurrency / in-flight bytes / runtime via the load guard.
        Map<String, List<NamedBytes>> terminals = loadGuard.execute(uploadBytes,
            () -> PipelineExecutor.runInMemory(
                model, node -> resolved.getOrDefault(node.id, List.of()), null));

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

    /** Office uploads → PDF bytes (gated + temp-dir exception); PDFs/images pass through raw. */
    private byte[] toSourceBytes(NamedBytes nb) throws PdfOperationException {
        if (DocumentConverter.classify(Path.of(nb.filename())) == DocumentConverter.Kind.OFFICE) {
            return uploads.toPdf(nb);
        }
        return nb.data();
    }
}
