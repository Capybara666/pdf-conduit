package com.pdfconduit.web.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.CompressResult;
import com.pdfconduit.core.model.ImageFormat;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.RedactRegion;
import com.pdfconduit.core.model.TextFormat;
import com.pdfconduit.core.service.OperationRunner.BatchOutcome;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.web.config.StartupConfig;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.dto.RedactRegionDto;
import com.pdfconduit.web.service.WebOperations;
import com.pdfconduit.web.support.Params;
import com.pdfconduit.web.support.Responses;
import com.pdfconduit.web.support.TempWorkspace;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static com.pdfconduit.web.web.ControllerSupport.ensurePdf;
import static com.pdfconduit.web.web.ControllerSupport.guardCount;
import static com.pdfconduit.web.web.ControllerSupport.saveAll;
import static com.pdfconduit.web.web.ControllerSupport.stem;

/**
 * The PDF operation endpoints. Every request follows the same shape: open a
 * per-request {@link TempWorkspace}, save the uploaded parts, delegate the actual
 * work to {@link WebOperations} (which reuses the shared core machinery), read the
 * result bytes <em>before</em> the workspace is closed, and stream them back.
 */
@RestController
@RequestMapping("/api")
public class OperationsController {

    private static final MediaType DOCX =
        MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final WebOperations ops;
    private final StartupConfig startup;
    private final int maxFiles;
    private final ObjectMapper json;

    public OperationsController(WebOperations ops, StartupConfig startup,
                                WebProperties props, ObjectMapper json) {
        this.ops = ops;
        this.startup = startup;
        this.maxFiles = props.maxFilesPerRequest();
        this.json = json;
    }

    private TempWorkspace workspace() throws IOException {
        return TempWorkspace.create(startup.baseWorkDir());
    }

    // -------------------------------------------------------------------- MERGE

    @PostMapping(value = "/merge", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> merge(@RequestParam("files") List<MultipartFile> files,
                                        @RequestParam(required = false) String outputName)
            throws IOException, PdfOperationException {
        guardCount(files, maxFiles);
        try (TempWorkspace ws = workspace()) {
            List<Path> inputs = saveAll(ws, files);
            String name = (outputName == null || outputName.isBlank())
                ? stem(inputs.get(0)) + OperationType.MERGE.suffix() + ".pdf"
                : ensurePdf(outputName);
            Path out = ws.newOutput(name);
            ops.merge(inputs, PageSize.FIT, out);
            return Responses.file(TempWorkspace.readAll(out), name, MediaType.APPLICATION_PDF);
        }
    }

    // ------------------------------------------------------------------ EXTRACT

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> extract(@RequestParam("file") MultipartFile file,
                                          @RequestParam(required = false) String pages,
                                          @RequestParam(defaultValue = "false") boolean separate)
            throws IOException, PdfOperationException, InvalidPageRangeException {
        try (TempWorkspace ws = workspace()) {
            Path in = ws.save(file);
            if (separate) {
                Path dir = ws.outputDir().resolve(stem(in) + "_pages");
                List<Path> outs = ops.extract(in, pages, true, null, dir);
                return Responses.zipFiles(outs, "extract_results.zip");
            }
            Path out = ws.newOutput(ops.outputName(OperationType.EXTRACT, in));
            List<Path> outs = ops.extract(in, pages, false, out, null);
            Path only = outs.get(0);
            return Responses.file(TempWorkspace.readAll(only), only.getFileName().toString(),
                MediaType.APPLICATION_PDF);
        }
    }

    // ----------------------------------------------------------------- COMPRESS

    @PostMapping(value = "/compress", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> compress(@RequestParam("files") List<MultipartFile> files,
                                           @RequestParam String targetSize)
            throws IOException, PdfOperationException, InvalidPageRangeException {
        guardCount(files, maxFiles);
        long target = Params.parseSize(targetSize);
        try (TempWorkspace ws = workspace()) {
            List<Path> inputs = saveAll(ws, files);
            if (inputs.size() == 1) {
                Path in = inputs.get(0);
                Path out = ws.newOutput(ops.outputName(OperationType.COMPRESS, in));
                CompressResult r = ops.compress(in, target, out);
                byte[] bytes = TempWorkspace.readAll(out);
                return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                            .filename(out.getFileName().toString()).build().toString())
                    .header("X-Target-Reached", String.valueOf(r.targetReached()))
                    .header("X-Original-Bytes", String.valueOf(r.originalBytes()))
                    .header("X-Result-Bytes", String.valueOf(r.resultBytes()))
                    .contentLength(bytes.length)
                    .body(bytes);
            }
            BatchOutcome outcome = ops.compressBatch(inputs, target, ws.outputDir());
            return Responses.batch("compress", outcome, MediaType.APPLICATION_PDF);
        }
    }

    // ------------------------------------------------------------------- ROTATE

    @PostMapping(value = "/rotate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> rotate(@RequestParam("files") List<MultipartFile> files,
                                         @RequestParam int angle,
                                         @RequestParam(required = false) String pages)
            throws IOException, PdfOperationException {
        guardCount(files, maxFiles);
        try (TempWorkspace ws = workspace()) {
            List<Path> inputs = saveAll(ws, files);
            BatchOutcome outcome = ops.rotate(inputs, pages, angle, ws.outputDir());
            return Responses.batch("rotate", outcome, MediaType.APPLICATION_PDF);
        }
    }

    // ------------------------------------------------------------------ ARRANGE

    @PostMapping(value = "/arrange", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> arrange(@RequestParam("file") MultipartFile file,
                                          @RequestParam String order)
            throws IOException, PdfOperationException, InvalidPageRangeException {
        Params.require(order, "order");
        try (TempWorkspace ws = workspace()) {
            Path in = ws.save(file);
            Path out = ws.newOutput(ops.outputName(OperationType.ARRANGE, in));
            ops.arrange(in, order, out);
            return Responses.file(TempWorkspace.readAll(out), out.getFileName().toString(),
                MediaType.APPLICATION_PDF);
        }
    }

    // ------------------------------------------------------------------- TO-PDF

    @PostMapping(value = "/to-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> toPdf(@RequestParam("files") List<MultipartFile> files,
                                        @RequestParam(required = false) String pageSize)
            throws IOException, PdfOperationException {
        guardCount(files, maxFiles);
        PageSize size = Params.pageSize(pageSize, PageSize.FIT);
        try (TempWorkspace ws = workspace()) {
            List<Path> inputs = saveAll(ws, files);
            BatchOutcome outcome = ops.toPdf(inputs, size, ws.outputDir());
            return Responses.batch("to-pdf", outcome, MediaType.APPLICATION_PDF);
        }
    }

    // ------------------------------------------------------------------ PROTECT

    @PostMapping(value = "/protect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> protect(@RequestParam("files") List<MultipartFile> files,
                                          @RequestParam String userPassword,
                                          @RequestParam(required = false) String ownerPassword)
            throws IOException, PdfOperationException {
        guardCount(files, maxFiles);
        Params.require(userPassword, "userPassword");
        try (TempWorkspace ws = workspace()) {
            List<Path> inputs = saveAll(ws, files);
            BatchOutcome outcome = ops.protect(inputs, userPassword, ownerPassword, ws.outputDir());
            return Responses.batch("protect", outcome, MediaType.APPLICATION_PDF);
        }
    }

    // ------------------------------------------------------------------- UNLOCK

    @PostMapping(value = "/unlock", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> unlock(@RequestParam("files") List<MultipartFile> files,
                                         @RequestParam String password)
            throws IOException, PdfOperationException {
        guardCount(files, maxFiles);
        Params.require(password, "password");
        try (TempWorkspace ws = workspace()) {
            List<Path> inputs = saveAll(ws, files);
            BatchOutcome outcome = ops.unlock(inputs, password, ws.outputDir());
            return Responses.batch("unlock", outcome, MediaType.APPLICATION_PDF);
        }
    }

    // ---------------------------------------------------------------- WATERMARK

    @PostMapping(value = "/watermark", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> watermark(@RequestParam("files") List<MultipartFile> files,
                                            @RequestParam(required = false) String text,
                                            @RequestParam(required = false) MultipartFile image,
                                            @RequestParam(required = false) Double opacity,
                                            @RequestParam(required = false) Double rotation,
                                            @RequestParam(required = false) Double scale)
            throws IOException, PdfOperationException {
        guardCount(files, maxFiles);
        boolean hasText = text != null && !text.isBlank();
        boolean hasImage = image != null && !image.isEmpty();
        if (hasText == hasImage) {
            throw new IllegalArgumentException("Provide either watermark text or an image, not both.");
        }
        try (TempWorkspace ws = workspace()) {
            List<Path> inputs = saveAll(ws, files);
            Path imagePath = hasImage ? ws.save(image) : null;
            BatchOutcome outcome = ops.watermark(inputs, hasText ? text : null, imagePath,
                opacity != null ? opacity : 0.3,
                rotation != null ? rotation : 45,
                scale != null ? scale : 0.5,
                ws.outputDir());
            return Responses.batch("watermark", outcome, MediaType.APPLICATION_PDF);
        }
    }

    // ------------------------------------------------------------------- REDACT

    @PostMapping(value = "/redact", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> redact(@RequestParam("file") MultipartFile file,
                                         @RequestParam String regions,
                                         @RequestParam(required = false) Integer dpi)
            throws IOException, PdfOperationException, InvalidPageRangeException {
        List<RedactRegion> parsed = parseRegions(regions);
        try (TempWorkspace ws = workspace()) {
            Path in = ws.save(file);
            Path out = ws.newOutput(ops.outputName(OperationType.REDACT, in));
            ops.redact(in, parsed, dpi != null ? dpi : 0, out);
            return Responses.file(TempWorkspace.readAll(out), out.getFileName().toString(),
                MediaType.APPLICATION_PDF);
        }
    }

    private List<RedactRegion> parseRegions(String regions) {
        Params.require(regions, "regions");
        try {
            RedactRegionDto[] dtos = json.readValue(regions, RedactRegionDto[].class);
            return java.util.Arrays.stream(dtos).map(RedactRegionDto::toRegion).toList();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid regions JSON: " + e.getOriginalMessage());
        }
    }

    // ----------------------------------------------------------------- TO-IMAGES

    @PostMapping(value = "/to-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> toImages(@RequestParam("file") MultipartFile file,
                                           @RequestParam(required = false) String format,
                                           @RequestParam(required = false) Integer dpi,
                                           @RequestParam(required = false) String pages)
            throws IOException, PdfOperationException, InvalidPageRangeException {
        ImageFormat fmt = Params.imageFormat(format, ImageFormat.PNG);
        try (TempWorkspace ws = workspace()) {
            Path in = ws.save(file);
            List<Path> images = ops.toImages(in, fmt, dpi != null ? dpi : 150, pages,
                0.8f, ws.outputDir(), stem(in));
            if (images.size() == 1) {
                Path only = images.get(0);
                MediaType type = fmt == ImageFormat.PNG ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
                return Responses.file(TempWorkspace.readAll(only), only.getFileName().toString(), type);
            }
            return Responses.zipFiles(images, "to-images_results.zip");
        }
    }

    // ------------------------------------------------------------------- TO-TEXT

    @PostMapping(value = "/to-text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> toText(@RequestParam("file") MultipartFile file,
                                         @RequestParam(required = false) String format,
                                         @RequestParam(required = false) String pages)
            throws IOException, PdfOperationException, InvalidPageRangeException {
        TextFormat fmt = Params.textFormat(format, TextFormat.TXT);
        try (TempWorkspace ws = workspace()) {
            Path in = ws.save(file);
            Path out = ops.toText(in, fmt, pages, ws.outputDir(), stem(in));
            MediaType type = fmt == TextFormat.TXT
                ? new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8)
                : DOCX;
            return Responses.file(TempWorkspace.readAll(out), out.getFileName().toString(), type);
        }
    }
}
