package com.pdfconduit.web.web;

import com.pdfconduit.core.analyze.PiiScanResult;
import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.pipeline.PipelineException;
import com.pdfconduit.core.service.NamedBytes;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.dto.BatchPiiReportDto;
import com.pdfconduit.web.dto.PiiReportDto;
import com.pdfconduit.web.guard.LoadGuard;
import com.pdfconduit.web.service.WebOperations;
import com.pdfconduit.web.support.Uploads;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

import static com.pdfconduit.web.web.ControllerSupport.guardCount;
import static com.pdfconduit.web.web.ControllerSupport.totalBytes;

/**
 * Document-analysis endpoints — stateless and fully in-memory. Unlike the operation endpoints
 * (which stream a file back), the GDPR / PII scans return a JSON report: the uploaded file is
 * routed to PDF if needed, scanned offline for personal data, and only masked samples ever leave
 * the server. Both the single-file scan and the batch audit are normal heavy, quota-consuming
 * operations and run under the {@link LoadGuard} like every other PDF-processing request.
 */
@RestController
@RequestMapping("/api")
public class AnalysisController {

    private final WebOperations ops;
    private final Uploads uploads;
    private final LoadGuard loadGuard;
    private final int maxFiles;

    public AnalysisController(WebOperations ops, Uploads uploads, LoadGuard loadGuard,
                              WebProperties props) {
        this.ops = ops;
        this.uploads = uploads;
        this.loadGuard = loadGuard;
        this.maxFiles = props.maxFilesPerRequest();
    }

    @PostMapping(value = "/gdpr-scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PiiReportDto gdprScan(@RequestParam("file") MultipartFile file)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        NamedBytes in = uploads.read(file);
        PiiScanResult result = loadGuard.execute(in.data().length, () -> ops.scanPii(in));
        return PiiReportDto.of(result);
    }

    /**
     * Batch GDPR compliance audit: scan several files at once and return one aggregated report — a
     * roll-up (file count, total findings, highest risk, category totals) plus each file's full
     * {@link PiiReportDto}. Every file is scanned offline in memory; nothing is stored.
     */
    @PostMapping(value = "/gdpr-scan-batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BatchPiiReportDto gdprScanBatch(@RequestParam("files") List<MultipartFile> files)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        List<NamedBytes> inputs = uploads.readAll(files);
        List<PiiScanResult> results = loadGuard.execute(totalBytes(inputs),
            () -> ops.scanPiiBatch(inputs));
        return BatchPiiReportDto.of(inputs.stream().map(NamedBytes::filename).toList(), results);
    }
}
