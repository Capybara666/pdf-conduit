package com.pdfconduit.web.dto;

import com.pdfconduit.core.model.PdfMetadata;

/** JSON view of a PDF's document-information metadata (any field may be {@code null}). */
public record MetadataDto(String title, String author, String subject, String keywords) {

    public static MetadataDto of(PdfMetadata m) {
        return new MetadataDto(m.title(), m.author(), m.subject(), m.keywords());
    }
}
