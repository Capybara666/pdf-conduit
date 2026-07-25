package com.pdfconduit.web;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Tiny in-memory PDF generators for the web tests (no files on disk, no LibreOffice). */
final class TestPdfs {

    private TestPdfs() {}

    /** A PDF with {@code pages} blank A4 pages. */
    static byte[] blank(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            return bytes(doc);
        }
    }

    /** A one-page PDF carrying a little text (so text/compress have something to chew on). */
    static byte[] withText(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            return bytes(doc);
        }
    }

    private static byte[] bytes(PDDocument doc) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.save(bos);
        return bos.toByteArray();
    }

    /** Extracted text of a PDF byte array — the ground truth for "did the data really go?". */
    static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
    }

    /** Number of pages in a PDF byte array (for assertions). */
    static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }
}
