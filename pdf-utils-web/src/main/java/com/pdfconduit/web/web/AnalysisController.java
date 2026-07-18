package com.pdfconduit.web.web;

import com.pdfconduit.core.analyze.PiiScanResult;
import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.pipeline.PipelineException;
import com.pdfconduit.core.service.NamedBytes;
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

/**
 * Document-analysis endpoints — stateless and fully in-memory. Unlike the operation endpoints
 * (which stream a file back), the GDPR / PII scan returns a JSON {@link PiiReportDto} report: the
 * uploaded file is routed to PDF if needed, scanned offline for personal data, and only masked
 * samples ever leave the server. The scan is a normal heavy, quota-consuming operation and runs
 * under the {@link LoadGuard} like every other PDF-processing request.
 */
@RestController
@RequestMapping("/api")
public class AnalysisController {

    private final WebOperations ops;
    private final Uploads uploads;
    private final LoadGuard loadGuard;

    public AnalysisController(WebOperations ops, Uploads uploads, LoadGuard loadGuard) {
        this.ops = ops;
        this.uploads = uploads;
        this.loadGuard = loadGuard;
    }

    @PostMapping(value = "/gdpr-scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PiiReportDto gdprScan(@RequestParam("file") MultipartFile file)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        NamedBytes in = uploads.read(file);
        PiiScanResult result = loadGuard.execute(in.data().length, () -> ops.scanPii(in));
        return PiiReportDto.of(result);
    }
}
