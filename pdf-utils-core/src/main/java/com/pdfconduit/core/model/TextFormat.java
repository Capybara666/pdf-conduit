package com.pdfconduit.core.model;

/**
 * Output format for {@link com.pdfconduit.core.operations.PdfTextExporter}. TXT is
 * extracted directly with PDFBox (no dependency); DOCX is produced by LibreOffice.
 */
public enum TextFormat {
    TXT("txt", null),
    DOCX("docx", "docx");

    private final String extension;
    private final String sofficeFormat;   // null when no LibreOffice conversion is needed

    TextFormat(String extension, String sofficeFormat) {
        this.extension = extension;
        this.sofficeFormat = sofficeFormat;
    }

    public String extension() { return extension; }

    /** The LibreOffice {@code --convert-to} format name, or {@code null} for native extraction. */
    public String sofficeFormat() { return sofficeFormat; }

    /** True when producing this format requires a LibreOffice install. */
    public boolean needsLibreOffice() { return sofficeFormat != null; }
}
