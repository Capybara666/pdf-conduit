package com.pdfconduit.web.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.pipeline.PipelineException;
import com.pdfconduit.core.model.CompressBytesResult;
import com.pdfconduit.core.model.ImageFormat;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.RedactRegion;
import com.pdfconduit.core.model.TextFormat;
import com.pdfconduit.core.service.MemoryOperations;
import com.pdfconduit.core.service.NamedBytes;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.dto.RedactRegionDto;
import com.pdfconduit.web.guard.LoadGuard;
import com.pdfconduit.web.service.WebOperations;
import com.pdfconduit.web.support.Params;
import com.pdfconduit.web.support.Responses;
import com.pdfconduit.web.support.Uploads;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static com.pdfconduit.web.web.ControllerSupport.ensurePdf;
import static com.pdfconduit.web.web.ControllerSupport.guardCount;
import static com.pdfconduit.web.web.ControllerSupport.totalBytes;

/**
 * The PDF operation endpoints — stateless and fully in-memory. Every request reads the
 * uploaded parts' bytes ({@link Uploads}), delegates the work to {@link WebOperations} (which
 * flows through the core {@code byte[]} API), and streams the resulting bytes back via
 * {@link ResponseEntity}. Multi-output / multi-file MAP results are zipped in memory. The one
 * disk touch is the documented office conversion, gated by {@code pdfconduit.web.office.enabled}.
 */
@RestController
@RequestMapping("/api")
public class OperationsController {

    private static final MediaType DOCX =
        MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final WebOperations ops;
    private final Uploads uploads;
    private final int maxFiles;
    private final ObjectMapper json;
    private final LoadGuard loadGuard;

    public OperationsController(WebOperations ops, Uploads uploads, WebProperties props,
                               ObjectMapper json, LoadGuard loadGuard) {
        this.ops = ops;
        this.uploads = uploads;
        this.maxFiles = props.maxFilesPerRequest();
        this.json = json;
        this.loadGuard = loadGuard;
    }

    // -------------------------------------------------------------------- MERGE

    @PostMapping(value = "/merge", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> merge(@RequestParam("files") List<MultipartFile> files,
                                        @RequestParam(required = false) String outputName)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        List<NamedBytes> inputs = uploads.readAll(files);
        NamedBytes merged = loadGuard.execute(totalBytes(inputs), () -> ops.merge(inputs));
        String name = (outputName == null || outputName.isBlank()) ? merged.filename() : ensurePdf(outputName);
        return Responses.file(merged.data(), name, MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------ EXTRACT

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> extract(@RequestParam("files") List<MultipartFile> files,
                                          @RequestParam(required = false) String pages,
                                          @RequestParam(defaultValue = "false") boolean separate)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        List<NamedBytes> inputs = uploads.readAll(files);
        long bytes = totalBytes(inputs);
        if (separate) {
            // Per-page split is inherently multi-output, so it always zips (even for a single file).
            return Responses.zip(loadGuard.execute(bytes, () -> ops.extractSeparate(inputs, pages)),
                "extract_results.zip");
        }
        // Combine: one PDF per input — a single file streams, several files zip.
        List<NamedBytes> results = loadGuard.execute(bytes, () -> ops.extractCombine(inputs, pages));
        return Responses.batch("extract", results, MediaType.APPLICATION_PDF);
    }

    // ----------------------------------------------------------------- COMPRESS

    @PostMapping(value = "/compress", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> compress(@RequestParam("files") List<MultipartFile> files,
                                           @RequestParam String targetSize)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        long target = Params.parseSize(targetSize);
        List<NamedBytes> inputs = uploads.readAll(files);
        long bytes = totalBytes(inputs);
        if (inputs.size() == 1) {
            NamedBytes in = inputs.get(0);
            CompressBytesResult r = loadGuard.execute(bytes, () -> ops.compress(in, target));
            String name = MemoryOperations.outputName(OperationType.COMPRESS, in.filename());
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, Responses.contentDisposition(name))
                .header("X-Target-Reached", String.valueOf(r.targetReached()))
                .header("X-Original-Bytes", String.valueOf(r.originalBytes()))
                .header("X-Result-Bytes", String.valueOf(r.resultBytes()))
                .contentLength(r.bytes().length)
                .body(r.bytes());
        }
        List<NamedBytes> results = loadGuard.execute(bytes, () -> ops.compressBatch(inputs, target));
        return Responses.zip(results, "compress_results.zip");
    }

    // ------------------------------------------------------------------- ROTATE

    @PostMapping(value = "/rotate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> rotate(@RequestParam("files") List<MultipartFile> files,
                                         @RequestParam int angle,
                                         @RequestParam(required = false) String pages)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        List<NamedBytes> inputs = uploads.readAll(files);
        List<NamedBytes> results = loadGuard.execute(totalBytes(inputs),
            () -> ops.rotate(inputs, pages, angle));
        return Responses.batch("rotate", results, MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------ ARRANGE

    @PostMapping(value = "/arrange", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> arrange(@RequestParam("file") MultipartFile file,
                                          @RequestParam String order)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        Params.require(order, "order");
        NamedBytes in = uploads.read(file);
        NamedBytes result = loadGuard.execute(in.data().length, () -> ops.arrange(in, order));
        return Responses.file(result, MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------- TO-PDF

    @PostMapping(value = "/to-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> toPdf(@RequestParam("files") List<MultipartFile> files,
                                        @RequestParam(required = false) String pageSize)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        PageSize size = Params.pageSize(pageSize, PageSize.FIT);
        List<NamedBytes> inputs = uploads.readAll(files);
        List<NamedBytes> results = loadGuard.execute(totalBytes(inputs), () -> ops.toPdf(inputs, size));
        return Responses.batch("to-pdf", results, MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------ PROTECT

    @PostMapping(value = "/protect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> protect(@RequestParam("files") List<MultipartFile> files,
                                          @RequestParam String userPassword,
                                          @RequestParam(required = false) String ownerPassword)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        Params.require(userPassword, "userPassword");
        List<NamedBytes> inputs = uploads.readAll(files);
        List<NamedBytes> results = loadGuard.execute(totalBytes(inputs),
            () -> ops.protect(inputs, userPassword, ownerPassword));
        return Responses.batch("protect", results, MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------- UNLOCK

    @PostMapping(value = "/unlock", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> unlock(@RequestParam("files") List<MultipartFile> files,
                                         @RequestParam String password)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        Params.require(password, "password");
        List<NamedBytes> inputs = uploads.readAll(files);
        List<NamedBytes> results = loadGuard.execute(totalBytes(inputs),
            () -> ops.unlock(inputs, password));
        return Responses.batch("unlock", results, MediaType.APPLICATION_PDF);
    }

    // ---------------------------------------------------------------- WATERMARK

    @PostMapping(value = "/watermark", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> watermark(@RequestParam("files") List<MultipartFile> files,
                                            @RequestParam(required = false) String text,
                                            @RequestParam(required = false) MultipartFile image,
                                            @RequestParam(required = false) Double opacity,
                                            @RequestParam(required = false) Double rotation,
                                            @RequestParam(required = false) Double scale)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        boolean hasText = text != null && !text.isBlank();
        boolean hasImage = image != null && !image.isEmpty();
        if (hasText == hasImage) {
            throw new IllegalArgumentException("Provide either watermark text or an image, not both.");
        }
        byte[] imageBytes = hasImage ? image.getBytes() : null;
        List<NamedBytes> inputs = uploads.readAll(files);
        long bytes = totalBytes(inputs) + (imageBytes != null ? imageBytes.length : 0);
        List<NamedBytes> results = loadGuard.execute(bytes,
            () -> ops.watermark(inputs, hasText ? text : null, imageBytes,
                opacity != null ? opacity : 0.3,
                rotation != null ? rotation : 45,
                scale != null ? scale : 0.5));
        return Responses.batch("watermark", results, MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------- REDACT

    @PostMapping(value = "/redact", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> redact(@RequestParam("file") MultipartFile file,
                                         @RequestParam String regions,
                                         @RequestParam(required = false) Integer dpi)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        List<RedactRegion> parsed = parseRegions(regions);
        NamedBytes in = uploads.read(file);
        int resolvedDpi = dpi != null ? dpi : 0;
        NamedBytes result = loadGuard.execute(in.data().length, () -> ops.redact(in, parsed, resolvedDpi));
        return Responses.file(result, MediaType.APPLICATION_PDF);
    }

    private List<RedactRegion> parseRegions(String regions) {
        Params.require(regions, "regions");
        try {
            RedactRegionDto[] dtos = json.readValue(regions, RedactRegionDto[].class);
            return Arrays.stream(dtos).map(RedactRegionDto::toRegion).toList();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid regions JSON: " + e.getOriginalMessage());
        }
    }

    // ----------------------------------------------------------------- TO-IMAGES

    @PostMapping(value = "/to-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> toImages(@RequestParam("files") List<MultipartFile> files,
                                           @RequestParam(required = false) String format,
                                           @RequestParam(required = false) Integer dpi,
                                           @RequestParam(required = false) String pages,
                                           @RequestParam(required = false) Float quality)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        ImageFormat fmt = Params.imageFormat(format, ImageFormat.PNG);
        int resolvedDpi = dpi != null ? dpi : 150;
        // JPEG quality clamped to [0.05, 1.0]; ignored for PNG (lossless). Default 0.8.
        float q = quality != null ? Math.max(0.05f, Math.min(1.0f, quality)) : 0.8f;
        List<NamedBytes> inputs = uploads.readAll(files);
        List<NamedBytes> images = loadGuard.execute(totalBytes(inputs),
            () -> ops.toImages(inputs, fmt, resolvedDpi, pages, q));
        MediaType type = fmt == ImageFormat.PNG ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return Responses.batch("to-images", images, type);
    }

    // ------------------------------------------------------------------- TO-TEXT

    @PostMapping(value = "/to-text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> toText(@RequestParam("files") List<MultipartFile> files,
                                         @RequestParam(required = false) String format,
                                         @RequestParam(required = false) String pages)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        TextFormat fmt = Params.textFormat(format, TextFormat.TXT);
        List<NamedBytes> inputs = uploads.readAll(files);
        List<NamedBytes> outputs = loadGuard.execute(totalBytes(inputs), () -> ops.toText(inputs, fmt, pages));
        MediaType type = fmt == TextFormat.TXT
            ? new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8)
            : DOCX;
        return Responses.batch("to-text", outputs, type);
    }
}
