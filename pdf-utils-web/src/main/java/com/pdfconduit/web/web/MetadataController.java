package com.pdfconduit.web.web;

import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PdfMetadata;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.web.config.StartupConfig;
import com.pdfconduit.web.dto.MetadataDto;
import com.pdfconduit.web.service.WebOperations;
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

/** Metadata read + edit endpoints (split out for clarity from the operation endpoints). */
@RestController
@RequestMapping("/api")
public class MetadataController {

    private final WebOperations ops;
    private final StartupConfig startup;

    public MetadataController(WebOperations ops, StartupConfig startup) {
        this.ops = ops;
        this.startup = startup;
    }

    private TempWorkspace workspace() throws IOException {
        return TempWorkspace.create(startup.baseWorkDir());
    }

    @PostMapping(value = "/metadata/read", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MetadataDto read(@RequestParam("file") MultipartFile file)
            throws IOException, PdfOperationException, InvalidPageRangeException {
        try (TempWorkspace ws = workspace()) {
            Path in = ws.save(file);
            PdfMetadata meta = ops.readMetadata(in, ws.newOutput("metadata-scratch.pdf"));
            return MetadataDto.of(meta);
        }
    }

    @PostMapping(value = "/metadata", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> edit(@RequestParam("file") MultipartFile file,
                                       @RequestParam(required = false) String title,
                                       @RequestParam(required = false) String author,
                                       @RequestParam(required = false) String subject,
                                       @RequestParam(required = false) String keywords,
                                       @RequestParam(defaultValue = "false") boolean strip)
            throws IOException, PdfOperationException, InvalidPageRangeException {
        try (TempWorkspace ws = workspace()) {
            Path in = ws.save(file);
            Path out = ws.newOutput(ops.outputName(OperationType.METADATA, in));
            ops.editMetadata(in, title, author, subject, keywords, strip, out);
            return Responses.file(TempWorkspace.readAll(out), out.getFileName().toString(),
                MediaType.APPLICATION_PDF);
        }
    }
}
