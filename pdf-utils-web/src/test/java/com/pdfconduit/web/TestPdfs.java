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

    /**
     * A one-page PDF padded out to roughly {@code approxBytes} with an unfiltered stream of
     * incompressible bytes — a realistically large upload (a scan, a photo-heavy report) that is
     * still cheap to parse, so a test can exercise byte-driven ceilings without waiting for real
     * page content to be processed.
     */
    static byte[] bulky(int approxBytes) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            byte[] filler = new byte[approxBytes];
            new java.util.Random(7).nextBytes(filler);
            org.apache.pdfbox.pdmodel.common.PDStream stream =
                new org.apache.pdfbox.pdmodel.common.PDStream(
                    doc, new java.io.ByteArrayInputStream(filler));
            // Hung off the page so the stream survives a save/reload cycle instead of being
            // dropped as an unreferenced object.
            page.getCOSObject().setItem(
                org.apache.pdfbox.cos.COSName.getPDFName("TestFiller"), stream);
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
