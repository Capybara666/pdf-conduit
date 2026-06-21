package com.pdfconduit.core.model;

/**
 * The editable document-information fields of a PDF. Any field may be {@code null}
 * when the document does not set it.
 */
public record PdfMetadata(String title, String author, String subject, String keywords) {}
