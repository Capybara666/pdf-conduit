package com.pdfconduit.web.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.pipeline.PipelineException;
import com.pdfconduit.core.model.CompressBytesResult;
import com.pdfconduit.core.model.CompressOptions;
import com.pdfconduit.core.model.ImageFormat;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.RedactRegion;
import com.pdfconduit.core.model.RepairBytesResult;
import com.pdfconduit.core.model.RepairFinding;
import com.pdfconduit.core.model.SignPlacement;
import com.pdfconduit.core.model.TextFormat;
import com.pdfconduit.core.model.WatermarkOptions;
import com.pdfconduit.core.service.BatchOutcome;
import com.pdfconduit.core.service.MemoryOperations;
import com.pdfconduit.core.service.NamedBytes;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.dto.RedactRegionDto;
import com.pdfconduit.web.dto.SignPlacementDto;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.pdfconduit.web.web.ControllerSupport.ensurePdf;
import static com.pdfconduit.web.web.ControllerSupport.guardCount;
import static com.pdfconduit.web.web.ControllerSupport.totalBytes;

/**
 * The PDF operation endpoints — stateless and fully in-memory. Every request reads the
 * uploaded parts' bytes ({@link Uploads}), delegates the work to {@link WebOperations} (which
 * flows through the core {@code byte[]} API), and streams the resulting bytes back via
 * {@link ResponseEntity}. Multi-output / multi-file MAP results are zipped in memory. The one
 * disk touch is the documented office conversion, gated by {@code pdfconduit.web.office.enabled}.
 *
 * <p><b>MAP batches are partial-tolerant</b>: each uploaded file is run on its own through
 * {@link MemoryOperations#mapPartial}, so one unusable input (password-protected, damaged, over the
 * page cap) costs the user that file instead of the whole upload. The successes come back zipped and
 * the skipped files are named in {@code X-Batch-Failures}; only when <em>every</em> input fails does
 * the request itself fail, with the first error now naming its file. REDUCE (merge) is untouched —
 * a merge missing an input is a different document, so it still fails as a whole.
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

    /**
     * Extract pages. Three shapes, one endpoint:
     * <ul>
     *   <li>default — the selected pages combined into one PDF per input;</li>
     *   <li>{@code separate=true} — one PDF per selected page (ZIP);</li>
     *   <li>{@code splitEvery=N} — "split every N pages": the selection cut into parts of N pages
     *       (ZIP). It is the general form of {@code separate}, which is exactly {@code splitEvery=1},
     *       so passing it overrides {@code separate}.</li>
     * </ul>
     */
    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> extract(@RequestParam("files") List<MultipartFile> files,
                                          @RequestParam(required = false) String pages,
                                          @RequestParam(defaultValue = "false") boolean separate,
                                          @RequestParam(required = false) Integer splitEvery)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        if (splitEvery != null && splitEvery < 1) {
            throw new IllegalArgumentException("splitEvery must be at least 1.");
        }
        List<NamedBytes> inputs = uploads.readAll(files);
        long bytes = totalBytes(inputs);
        if (splitEvery != null || separate) {
            // A multi-part split is inherently multi-output, so it always zips (even for one file).
            int perPart = splitEvery != null ? splitEvery : 1;
            BatchOutcome outcome = loadGuard.execute(bytes, () -> MemoryOperations.mapPartial(inputs,
                in -> ops.extractSeparate(List.of(in), pages, perPart)));
            return Responses.zip(outcome, "extract_results.zip");
        }
        // Combine: one PDF per input — a single file streams, several files zip.
        BatchOutcome results = loadGuard.execute(bytes,
            () -> MemoryOperations.mapPartial(inputs, in -> ops.extractCombine(List.of(in), pages)));
        return Responses.batch("extract", results, MediaType.APPLICATION_PDF);
    }

    // ----------------------------------------------------------------- COMPRESS

    @PostMapping(value = "/compress", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> compress(@RequestParam("files") List<MultipartFile> files,
                                           @RequestParam String targetSize,
                                           @RequestParam(required = false) String dpi,
                                           @RequestParam(defaultValue = "false") boolean grayscale)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        long target = Params.parseSize(targetSize);
        // Optional image-resolution ceiling: SCREEN/EBOOK/PRINT (unknown/blank ⇒ NONE = no cap).
        CompressOptions.DpiPreset preset =
            parseEnum(CompressOptions.DpiPreset.class, dpi, CompressOptions.DpiPreset.NONE);
        List<NamedBytes> inputs = uploads.readAll(files);
        long bytes = totalBytes(inputs);
        if (inputs.size() == 1) {
            NamedBytes in = inputs.get(0);
            CompressBytesResult r =
                loadGuard.execute(bytes, () -> ops.compress(in, target, preset, grayscale));
            String name = MemoryOperations.outputName(OperationType.COMPRESS, in.filename());
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, Responses.contentDisposition(name))
                .header("X-Target-Reached", String.valueOf(r.targetReached()))
                .header("X-Target-Feasible", String.valueOf(r.targetReached()))
                .header("X-Estimated-Floor-Bytes", String.valueOf(r.resultBytes()))
                .header("X-Original-Bytes", String.valueOf(r.originalBytes()))
                .header("X-Result-Bytes", String.valueOf(r.resultBytes()))
                .contentLength(r.bytes().length)
                .body(r.bytes());
        }
        BatchOutcome results = loadGuard.execute(bytes, () -> MemoryOperations.mapPartial(inputs,
            in -> ops.compressBatch(List.of(in), target, preset, grayscale)));
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
        BatchOutcome results = loadGuard.execute(totalBytes(inputs),
            () -> MemoryOperations.mapPartial(inputs, in -> ops.rotate(List.of(in), pages, angle)));
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
        BatchOutcome results = loadGuard.execute(totalBytes(inputs),
            () -> MemoryOperations.mapPartial(inputs, in -> ops.toPdf(List.of(in), size)));
        return Responses.batch("to-pdf", results, MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------ PROTECT

    @PostMapping(value = "/protect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> protect(@RequestParam("files") List<MultipartFile> files,
                                          @RequestParam String userPassword,
                                          @RequestParam(required = false) String ownerPassword,
                                          @RequestParam(required = false) Integer keyLength)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        Params.require(userPassword, "userPassword");
        // Absent / any non-256 value → AES-128 (the compatibility default); 256 → AES-256.
        int bits = keyLength != null && keyLength == 256 ? 256 : 128;
        List<NamedBytes> inputs = uploads.readAll(files);
        BatchOutcome results = loadGuard.execute(totalBytes(inputs), () -> MemoryOperations.mapPartial(
            inputs, in -> ops.protect(List.of(in), userPassword, ownerPassword, bits)));
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
        BatchOutcome results = loadGuard.execute(totalBytes(inputs),
            () -> MemoryOperations.mapPartial(inputs, in -> ops.unlock(List.of(in), password)));
        return Responses.batch("unlock", results, MediaType.APPLICATION_PDF);
    }

    // ---------------------------------------------------------------- WATERMARK

    @PostMapping(value = "/watermark", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> watermark(@RequestParam("files") List<MultipartFile> files,
                                            @RequestParam(required = false) String text,
                                            @RequestParam(required = false) MultipartFile image,
                                            @RequestParam(required = false) Double opacity,
                                            @RequestParam(required = false) Double rotation,
                                            @RequestParam(required = false) Double scale,
                                            @RequestParam(required = false) String layout,
                                            @RequestParam(required = false) String position,
                                            @RequestParam(required = false) String color)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        boolean hasText = text != null && !text.isBlank();
        boolean hasImage = image != null && !image.isEmpty();
        if (hasText == hasImage) {
            throw new IllegalArgumentException("Provide either watermark text or an image, not both.");
        }
        byte[] imageBytes = hasImage ? image.getBytes() : null;
        var wmLayout = parseEnum(WatermarkOptions.Layout.class, layout, WatermarkOptions.Layout.SINGLE);
        var wmPosition = parseEnum(WatermarkOptions.Position.class, position, WatermarkOptions.Position.CENTER);
        List<NamedBytes> inputs = uploads.readAll(files);
        long bytes = totalBytes(inputs) + (imageBytes != null ? imageBytes.length : 0);
        BatchOutcome results = loadGuard.execute(bytes, () -> MemoryOperations.mapPartial(inputs,
            in -> ops.watermark(List.of(in), hasText ? text : null, imageBytes,
                opacity != null ? opacity : 0.3,
                rotation != null ? rotation : 45,
                scale != null ? scale : 0.5,
                wmLayout, wmPosition, color)));
        return Responses.batch("watermark", results, MediaType.APPLICATION_PDF);
    }

    /** Case-insensitive enum parse for optional request params; falls back on null/unknown. */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // --------------------------------------------------------------------- CROP

    @PostMapping(value = "/crop", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> crop(@RequestParam("files") List<MultipartFile> files,
                                       @RequestParam(required = false) Double top,
                                       @RequestParam(required = false) Double right,
                                       @RequestParam(required = false) Double bottom,
                                       @RequestParam(required = false) Double left,
                                       @RequestParam(required = false) String unit)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        // Margins are points by default; "mm" switches to millimetres. Missing edge ⇒ 0 (no trim).
        boolean mm = unit != null && unit.trim().equalsIgnoreCase("mm");
        double t = top != null ? top : 0, r = right != null ? right : 0;
        double b = bottom != null ? bottom : 0, l = left != null ? left : 0;
        List<NamedBytes> inputs = uploads.readAll(files);
        BatchOutcome results = loadGuard.execute(totalBytes(inputs),
            () -> MemoryOperations.mapPartial(inputs, in -> ops.crop(List.of(in), t, r, b, l, mm)));
        return Responses.batch("crop", results, MediaType.APPLICATION_PDF);
    }

    // ---------------------------------------------------------------------- NUP

    @PostMapping(value = "/nup", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> nup(@RequestParam("files") List<MultipartFile> files,
                                      @RequestParam(required = false) String layout,
                                      @RequestParam(defaultValue = "false") boolean booklet)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        var nupLayout = com.pdfconduit.core.model.NupLayout.fromId(layout);
        List<NamedBytes> inputs = uploads.readAll(files);
        BatchOutcome results = loadGuard.execute(totalBytes(inputs),
            () -> MemoryOperations.mapPartial(inputs, in -> ops.nup(List.of(in), nupLayout, booklet)));
        return Responses.batch("nup", results, MediaType.APPLICATION_PDF);
    }

    // --------------------------------------------------------------- PAGE-MARKS

    @PostMapping(value = "/page-marks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> pageMarks(@RequestParam("files") List<MultipartFile> files,
                                            @RequestParam(required = false) String headerLeft,
                                            @RequestParam(required = false) String headerCenter,
                                            @RequestParam(required = false) String headerRight,
                                            @RequestParam(required = false) String footerLeft,
                                            @RequestParam(required = false) String footerCenter,
                                            @RequestParam(required = false) String footerRight,
                                            @RequestParam(required = false) Float fontSize,
                                            @RequestParam(required = false) Float margin,
                                            @RequestParam(defaultValue = "false") boolean skipFirst,
                                            @RequestParam(required = false) Integer startNumber,
                                            @RequestParam(required = false) String prefix)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        List<NamedBytes> inputs = uploads.readAll(files);
        BatchOutcome results = loadGuard.execute(totalBytes(inputs), () -> MemoryOperations.mapPartial(
            inputs, in -> ops.pageMarks(List.of(in), headerLeft, headerCenter, headerRight,
                footerLeft, footerCenter, footerRight,
                fontSize != null ? fontSize : 10f,
                margin != null ? margin : 36f,
                skipFirst,
                startNumber != null ? startNumber : 1,
                prefix)));
        return Responses.batch("page-marks", results, MediaType.APPLICATION_PDF);
    }

    // ------------------------------------------------------------------- REPAIR

    /**
     * Tries to repair damaged PDFs — not every file can be recovered.
     *
     * <p>A single upload streams the rebuilt PDF back and carries the honest outcome in two headers:
     * {@code X-Repair-Was-Damaged} (the input failed a strict parse or showed a concrete structural
     * defect) and {@code X-Repair-Recovered} (a damaged input was rebuilt into a file that now
     * <em>does</em> parse strictly — verified, not assumed). {@code X-Repair-Findings} lists the
     * defects found, and the byte/page counters mirror compress's metric headers. A batch zips like
     * every other MAP operation; per-file metrics are not representable in a ZIP, so the headers are
     * single-file only.
     *
     * <p>A file with too little surviving structure fails with 422 {@code repair_failed}.
     */
    @PostMapping(value = "/repair", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> repair(@RequestParam("files") List<MultipartFile> files)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        List<NamedBytes> inputs = uploads.readAll(files);
        long bytes = totalBytes(inputs);
        if (inputs.size() == 1) {
            NamedBytes in = inputs.get(0);
            RepairBytesResult r = loadGuard.execute(bytes, () -> ops.repair(in));
            String findings = r.findings().stream().map(RepairFinding::id)
                .collect(java.util.stream.Collectors.joining(","));
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    Responses.contentDisposition(WebOperations.repairedName(in)))
                .header("X-Repair-Recovered", String.valueOf(r.recovered()))
                .header("X-Repair-Was-Damaged", String.valueOf(r.wasDamaged()))
                .header("X-Repair-Findings", findings)
                .header("X-Repair-Pages", String.valueOf(r.pageCount()))
                .header("X-Original-Bytes", String.valueOf(r.originalBytes()))
                .header("X-Result-Bytes", String.valueOf(r.resultBytes()))
                .contentLength(r.bytes().length)
                .body(r.bytes());
        }
        BatchOutcome results = loadGuard.execute(bytes,
            () -> MemoryOperations.mapPartial(inputs, in -> ops.repairBatch(List.of(in))));
        return Responses.zip(results, "repair_results.zip");
    }

    // ------------------------------------------------------------------- REDACT

    @PostMapping(value = "/redact", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> redact(@RequestParam("file") MultipartFile file,
                                         @RequestParam String regions,
                                         @RequestParam(required = false) Integer dpi,
                                         @RequestParam(defaultValue = "false") boolean reOcr)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        List<RedactRegion> parsed = parseRegions(regions);
        NamedBytes in = uploads.read(file);
        int resolvedDpi = dpi != null ? dpi : 0;
        NamedBytes result = loadGuard.execute(in.data().length, () -> ops.redact(in, parsed, resolvedDpi, reOcr));
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

    // --------------------------------------------------------------------- SIGN

    @PostMapping(value = "/sign", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> sign(@RequestParam("file") MultipartFile file,
                                       @RequestParam(name = "signatures", required = false)
                                           List<MultipartFile> signatures,
                                       @RequestParam(required = false) String placements,
                                       @RequestParam(required = false) String fields,
                                       @RequestParam(defaultValue = "false") boolean flatten)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        List<byte[]> images = readImages(signatures);
        List<SignPlacement> parsedPlacements = parsePlacements(placements);
        Map<String, String> parsedFields = parseFields(fields);
        if (images.isEmpty() && parsedFields.isEmpty()) {
            throw new IllegalArgumentException(
                "Nothing to do: provide at least one signature placement or a field value.");
        }
        NamedBytes in = uploads.read(file);
        long bytes = in.data().length + images.stream().mapToLong(b -> b.length).sum();
        NamedBytes result = loadGuard.execute(bytes,
            () -> ops.sign(in, images, parsedPlacements, parsedFields, flatten));
        return Responses.file(result, MediaType.APPLICATION_PDF);
    }

    /** Reads the signature image parts (may be absent) to raw bytes, order preserved. */
    private static List<byte[]> readImages(List<MultipartFile> signatures) throws IOException {
        List<byte[]> images = new ArrayList<>();
        if (signatures == null) return images;
        for (MultipartFile mf : signatures) {
            if (mf != null && !mf.isEmpty()) images.add(mf.getBytes());
        }
        return images;
    }

    private List<SignPlacement> parsePlacements(String placements) {
        if (placements == null || placements.isBlank()) return List.of();
        try {
            SignPlacementDto[] dtos = json.readValue(placements, SignPlacementDto[].class);
            return Arrays.stream(dtos).map(SignPlacementDto::toPlacement).toList();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid placements JSON: " + e.getOriginalMessage());
        }
    }

    private Map<String, String> parseFields(String fields) {
        if (fields == null || fields.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = json.readValue(fields,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Map<String, String> out = new java.util.LinkedHashMap<>();
            // Coerce any JSON scalar (string/number/boolean) to its string form; the core signer
            // interprets checkbox truthiness and sets text verbatim.
            raw.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
            return out;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid fields JSON: " + e.getOriginalMessage());
        }
    }

    // -------------------------------------------------------------- AUTO-REDACT

    /**
     * One-click auto-redaction driven by the PII scan (free): scan the upload offline, then black
     * out every detected value's regions — no manual box drawing. An optional {@code categories}
     * filter (comma-separated {@code PiiCategory} names, e.g. {@code FINANCIAL,NATIONAL_ID}) limits
     * which categories are redacted; blank ⇒ redact everything detected. Streams back the redacted
     * PDF. Nothing is stored; the whole flow runs in memory under the load guard.
     */
    @PostMapping(value = "/auto-redact", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> autoRedact(@RequestParam("file") MultipartFile file,
                                             @RequestParam(required = false) String categories)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        java.util.Set<com.pdfconduit.core.analyze.PiiCategory> cats = parseCategories(categories);
        NamedBytes in = uploads.read(file);
        NamedBytes result = loadGuard.execute(in.data().length, () -> ops.autoRedact(in, cats));
        return Responses.file(result, MediaType.APPLICATION_PDF);
    }

    /** Lenient parse of a comma-separated GDPR category filter; unknown names are ignored. */
    private static java.util.Set<com.pdfconduit.core.analyze.PiiCategory> parseCategories(String categories) {
        if (categories == null || categories.isBlank()) return java.util.Set.of();
        var out = java.util.EnumSet.noneOf(com.pdfconduit.core.analyze.PiiCategory.class);
        for (String token : categories.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(com.pdfconduit.core.analyze.PiiCategory.valueOf(
                    t.toUpperCase(java.util.Locale.ROOT).replace('-', '_')));
            } catch (IllegalArgumentException ignored) {
                // Unknown category name → skip it (lenient); an all-unknown filter redacts nothing.
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------- OCR

    @PostMapping(value = "/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> ocr(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) String languages)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        NamedBytes in = uploads.read(file);
        NamedBytes result = loadGuard.execute(in.data().length, () -> ops.ocr(in, languages));
        return Responses.file(result, MediaType.APPLICATION_PDF);
    }

    // ----------------------------------------------------------------- TO-IMAGES

    @PostMapping(value = "/to-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> toImages(@RequestParam("files") List<MultipartFile> files,
                                           @RequestParam(required = false) String format,
                                           @RequestParam(required = false) Integer dpi,
                                           @RequestParam(required = false) String pages,
                                           @RequestParam(required = false) Float quality,
                                           @RequestParam(required = false) Boolean transparent,
                                           @RequestParam(required = false) Boolean grayscale)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        ImageFormat fmt = Params.imageFormat(format, ImageFormat.PNG);
        int resolvedDpi = dpi != null ? dpi : 150;
        // JPEG quality clamped to [0.05, 1.0]; ignored for PNG (lossless). Default 0.8.
        float q = quality != null ? Math.max(0.05f, Math.min(1.0f, quality)) : 0.8f;
        // Transparent background applies to PNG only (JPEG has no alpha); both default false.
        boolean transparentBg = transparent != null && transparent;
        boolean gray = grayscale != null && grayscale;
        List<NamedBytes> inputs = uploads.readAll(files);
        BatchOutcome images = loadGuard.execute(totalBytes(inputs), () -> MemoryOperations.mapPartial(
            inputs, in -> ops.toImages(List.of(in), fmt, resolvedDpi, pages, q, transparentBg, gray)));
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
        BatchOutcome outputs = loadGuard.execute(totalBytes(inputs),
            () -> MemoryOperations.mapPartial(inputs, in -> ops.toText(List.of(in), fmt, pages)));
        MediaType type = fmt == TextFormat.TXT
            ? new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8)
            : DOCX;
        return Responses.batch("to-text", outputs, type);
    }
}
