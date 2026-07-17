package com.pdfconduit.web.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
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
import com.pdfconduit.web.service.WebOperations;
import com.pdfconduit.web.support.Params;
import com.pdfconduit.web.support.Responses;
import com.pdfconduit.web.support.Uploads;
import org.springframework.http.ContentDisposition;
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

    public OperationsController(WebOperations ops, Uploads uploads, WebProperties props, ObjectMapper json) {
        this.ops = ops;
        this.uploads = uploads;
        this.maxFiles = props.maxFilesPerRequest();
        this.json = json;
    }

    // -------------------------------------------------------------------- MERGE

    @PostMapping(value = "/merge", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> merge(@RequestParam("files") List<MultipartFile> files,
                                        @RequestParam(required = false) String outputName)
            throws IOException, PdfOperationException {
        guardCount(files, maxFiles);
        NamedBytes merged = ops.merge(uploads.readAll(files));
        String name = (outputName == null || outputName.isBlank()) ? merged.filename() : ensurePdf(outputName);
        return Responses.file(merged.data(), name, MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------ EXTRACT

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> extract(@RequestParam("file") MultipartFile file,
                                          @RequestParam(required = false) String pages,
                                          @RequestParam(defaultValue = "false") boolean separate)
            throws IOException, PdfOperationException, InvalidPageRangeException {
        NamedBytes in = uploads.read(file);
        if (separate) {
            return Responses.zip(ops.extractSeparate(in, pages), "extract_results.zip");
        }
        return Responses.file(ops.extractCombine(in, pages), MediaType.APPLICATION_PDF);
    }

    // ----------------------------------------------------------------- COMPRESS

    @PostMapping(value = "/compress", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> compress(@RequestParam("files") List<MultipartFile> files,
                                           @RequestParam String targetSize)
            throws IOException, PdfOperationException {
        guardCount(files, maxFiles);
        long target = Params.parseSize(targetSize);
        List<NamedBytes> inputs = uploads.readAll(files);
        if (inputs.size() == 1) {
            NamedBytes in = inputs.get(0);
            CompressBytesResult r = ops.compress(in, target);
            String name = MemoryOperations.outputName(OperationType.COMPRESS, in.filename());
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(name).build().toString())
                .header("X-Target-Reached", String.valueOf(r.targetReached()))
                .header("X-Original-Bytes", String.valueOf(r.originalBytes()))
                .header("X-Result-Bytes", String.valueOf(r.resultBytes()))
                .contentLength(r.bytes().length)
                .body(r.bytes());
        }
        return Responses.zip(ops.compressBatch(inputs, target), "compress_results.zip");
    }

    // ------------------------------------------------------------------- ROTATE

    @PostMapping(value = "/rotate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> rotate(@RequestParam("files") List<MultipartFile> files,
                                         @RequestParam int angle,
                                         @RequestParam(required = false) String pages)
            throws IOException, PdfOperationException, InvalidPageRangeException {
        guardCount(files, maxFiles);
        return Responses.batch("rotate", ops.rotate(uploads.readAll(files), pages, angle),
            MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------ ARRANGE

    @PostMapping(value = "/arrange", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> arrange(@RequestParam("file") MultipartFile file,
                                          @RequestParam String order)
            throws IOException, PdfOperationException, InvalidPageRangeException {
        Params.require(order, "order");
        return Responses.file(ops.arrange(uploads.read(file), order), MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------- TO-PDF

    @PostMapping(value = "/to-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> toPdf(@RequestParam("files") List<MultipartFile> files,
                                        @RequestParam(required = false) String pageSize)
            throws IOException, PdfOperationException {
        guardCount(files, maxFiles);
        PageSize size = Params.pageSize(pageSize, PageSize.FIT);
        return Responses.batch("to-pdf", ops.toPdf(uploads.readAll(files), size),
            MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------ PROTECT

    @PostMapping(value = "/protect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> protect(@RequestParam("files") List<MultipartFile> files,
                                          @RequestParam String userPassword,
                                          @RequestParam(required = false) String ownerPassword)
            throws IOException, PdfOperationException {
        guardCount(files, maxFiles);
        Params.require(userPassword, "userPassword");
        return Responses.batch("protect", ops.protect(uploads.readAll(files), userPassword, ownerPassword),
            MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------- UNLOCK

    @PostMapping(value = "/unlock", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> unlock(@RequestParam("files") List<MultipartFile> files,
                                         @RequestParam String password)
            throws IOException, PdfOperationException {
        guardCount(files, maxFiles);
        Params.require(password, "password");
        return Responses.batch("unlock", ops.unlock(uploads.readAll(files), password),
            MediaType.APPLICATION_PDF);
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
        byte[] imageBytes = hasImage ? image.getBytes() : null;
        return Responses.batch("watermark",
            ops.watermark(uploads.readAll(files), hasText ? text : null, imageBytes,
                opacity != null ? opacity : 0.3,
                rotation != null ? rotation : 45,
                scale != null ? scale : 0.5),
            MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------- REDACT

    @PostMapping(value = "/redact", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> redact(@RequestParam("file") MultipartFile file,
                                         @RequestParam String regions,
                                         @RequestParam(required = false) Integer dpi)
            throws IOException, PdfOperationException {
        List<RedactRegion> parsed = parseRegions(regions);
        return Responses.file(ops.redact(uploads.read(file), parsed, dpi != null ? dpi : 0),
            MediaType.APPLICATION_PDF);
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
    public ResponseEntity<byte[]> toImages(@RequestParam("file") MultipartFile file,
                                           @RequestParam(required = false) String format,
                                           @RequestParam(required = false) Integer dpi,
                                           @RequestParam(required = false) String pages)
            throws IOException, PdfOperationException, InvalidPageRangeException {
        ImageFormat fmt = Params.imageFormat(format, ImageFormat.PNG);
        List<NamedBytes> images = ops.toImages(uploads.read(file), fmt,
            dpi != null ? dpi : 150, pages, 0.8f);
        if (images.size() == 1) {
            MediaType type = fmt == ImageFormat.PNG ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
            return Responses.file(images.get(0), type);
        }
        return Responses.zip(images, "to-images_results.zip");
    }

    // ------------------------------------------------------------------- TO-TEXT

    @PostMapping(value = "/to-text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> toText(@RequestParam("file") MultipartFile file,
                                         @RequestParam(required = false) String format,
                                         @RequestParam(required = false) String pages)
            throws IOException, PdfOperationException, InvalidPageRangeException {
        TextFormat fmt = Params.textFormat(format, TextFormat.TXT);
        NamedBytes out = ops.toText(uploads.read(file), fmt, pages);
        MediaType type = fmt == TextFormat.TXT
            ? new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8)
            : DOCX;
        return Responses.file(out, type);
    }
}
