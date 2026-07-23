package com.pdfconduit.core.model;

/**
 * Output format for {@link com.pdfconduit.core.operations.PdfTextExporter}. Both are produced
 * natively — TXT via PDFBox extraction, DOCX as an OOXML package built in memory (no LibreOffice,
 * no extra dependency). Neither requires an external process.
 */
public enum TextFormat {
    TXT("txt", null),
    DOCX("docx", null);

    private final String extension;
    private final String sofficeFormat;   // retained for API stability; always null today

    TextFormat(String extension, String sofficeFormat) {
        this.extension = extension;
        this.sofficeFormat = sofficeFormat;
    }

    public String extension() { return extension; }

    /** The LibreOffice {@code --convert-to} format name, or {@code null} for native output. */
    public String sofficeFormat() { return sofficeFormat; }

    /** True when producing this format requires a LibreOffice install (none do today). */
    public boolean needsLibreOffice() { return sofficeFormat != null; }
}
