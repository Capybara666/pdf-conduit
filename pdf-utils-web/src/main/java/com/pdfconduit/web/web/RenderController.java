package com.pdfconduit.web.web;

import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.web.service.WebOperations;
import com.pdfconduit.web.support.Uploads;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Server-side page rendering: turns one page of an uploaded document into a PNG. The Angular
 * frontend renders PDFs client-side with pdf.js, but this endpoint is a fallback for thumbnails
 * and non-PDF inputs. Fully in-memory.
 */
@RestController
@RequestMapping("/api")
public class RenderController {

    private final WebOperations ops;
    private final Uploads uploads;

    public RenderController(WebOperations ops, Uploads uploads) {
        this.ops = ops;
        this.uploads = uploads;
    }

    @PostMapping(value = "/render", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> render(@RequestParam("file") MultipartFile file,
                                         @RequestParam int page,
                                         @RequestParam(required = false) Integer dpi)
            throws IOException, PdfOperationException, InvalidPageRangeException {
        byte[] png = ops.renderPage(uploads.read(file), page, dpi != null ? dpi : 120);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(png.length).body(png);
    }
}
