package com.pdfconduit.web.web;

import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PdfMetadata;
import com.pdfconduit.core.pipeline.PipelineException;
import com.pdfconduit.core.service.NamedBytes;
import com.pdfconduit.web.config.WebProperties;
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
import java.util.List;

import static com.pdfconduit.web.web.ControllerSupport.guardCount;
import static com.pdfconduit.web.web.ControllerSupport.totalBytes;

/** Metadata read + edit endpoints (in-memory; split out for clarity from the operation endpoints). */
@RestController
@RequestMapping("/api")
public class MetadataController {

    private final WebOperations ops;
    private final Uploads uploads;
    private final int maxFiles;
    private final LoadGuard loadGuard;

    public MetadataController(WebOperations ops, Uploads uploads, WebProperties props, LoadGuard loadGuard) {
        this.ops = ops;
        this.uploads = uploads;
        this.maxFiles = props.maxFilesPerRequest();
        this.loadGuard = loadGuard;
    }

    @PostMapping(value = "/metadata/read", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MetadataDto read(@RequestParam("file") MultipartFile file)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        NamedBytes in = uploads.read(file);
        PdfMetadata meta = loadGuard.execute(in.data().length, () -> ops.readMetadata(in));
        return MetadataDto.of(meta);
    }

    @PostMapping(value = "/metadata", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> edit(@RequestParam("files") List<MultipartFile> files,
                                       @RequestParam(required = false) String title,
                                       @RequestParam(required = false) String author,
                                       @RequestParam(required = false) String subject,
                                       @RequestParam(required = false) String keywords,
                                       @RequestParam(defaultValue = "false") boolean strip)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        guardCount(files, maxFiles);
        List<NamedBytes> inputs = uploads.readAll(files);
        List<NamedBytes> results = loadGuard.execute(totalBytes(inputs),
            () -> ops.editMetadata(inputs, title, author, subject, keywords, strip));
        return Responses.batch("metadata", results, MediaType.APPLICATION_PDF);
    }
}
