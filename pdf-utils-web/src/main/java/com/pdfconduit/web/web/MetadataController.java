package com.pdfconduit.web.web;

import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PdfMetadata;
import com.pdfconduit.core.pipeline.PipelineException;
import com.pdfconduit.core.service.NamedBytes;
import com.pdfconduit.web.dto.MetadataDto;
import com.pdfconduit.web.guard.LoadGuard;
import com.pdfconduit.web.service.WebOperations;
import com.pdfconduit.web.support.Responses;
import com.pdfconduit.web.support.Uploads;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** Metadata read + edit endpoints (in-memory; split out for clarity from the operation endpoints). */
@RestController
@RequestMapping("/api")
public class MetadataController {

    private final WebOperations ops;
    private final Uploads uploads;
    private final LoadGuard loadGuard;

    public MetadataController(WebOperations ops, Uploads uploads, LoadGuard loadGuard) {
        this.ops = ops;
        this.uploads = uploads;
        this.loadGuard = loadGuard;
    }

    @PostMapping(value = "/metadata/read", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MetadataDto read(@RequestParam("file") MultipartFile file)
            throws IOException, PdfOperationException {
        PdfMetadata meta = ops.readMetadata(uploads.read(file));
        return MetadataDto.of(meta);
    }

    @PostMapping(value = "/metadata", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> edit(@RequestParam("file") MultipartFile file,
                                       @RequestParam(required = false) String title,
                                       @RequestParam(required = false) String author,
                                       @RequestParam(required = false) String subject,
                                       @RequestParam(required = false) String keywords,
                                       @RequestParam(defaultValue = "false") boolean strip)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        NamedBytes in = uploads.read(file);
        NamedBytes result = loadGuard.execute(in.data().length,
            () -> ops.editMetadata(in, title, author, subject, keywords, strip));
        return Responses.file(result, MediaType.APPLICATION_PDF);
    }
}
