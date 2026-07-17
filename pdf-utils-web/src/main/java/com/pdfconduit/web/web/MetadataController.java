package com.pdfconduit.web.web;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PdfMetadata;
import com.pdfconduit.web.dto.MetadataDto;
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

    public MetadataController(WebOperations ops, Uploads uploads) {
        this.ops = ops;
        this.uploads = uploads;
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
            throws IOException, PdfOperationException {
        return Responses.file(
            ops.editMetadata(uploads.read(file), title, author, subject, keywords, strip),
            MediaType.APPLICATION_PDF);
    }
}
