package com.pdfconduit.core.operations;

import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PdfToTextOptions;
import com.pdfconduit.core.model.PdfToTextResult;
import com.pdfconduit.core.model.TextFormat;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class PdfTextExporterTest {

    @TempDir Path tmp;

    @Test
    void extractsAllPagesToTxt() throws Exception {
        Path src = createPdf("ALPHA", "BETA", "GAMMA");
        Path dir = tmp.resolve("out");

        PdfToTextResult result = PdfTextExporter.execute(
            new PdfToTextOptions(src, TextFormat.TXT, PageRange.ALL, dir, "doc"));

        assertEquals(dir.resolve("doc.txt"), result.output());
        String text = Files.readString(result.output());
        assertTrue(text.contains("ALPHA"), text);
        assertTrue(text.contains("BETA"), text);
        assertTrue(text.contains("GAMMA"), text);
    }

    @Test
    void extractsOnlySelectedPages() throws Exception {
        Path src = createPdf("ALPHA", "BETA", "GAMMA");
        Path dir = tmp.resolve("sub");

        PdfTextExporter.execute(
            new PdfToTextOptions(src, TextFormat.TXT, new PageRange(List.of(2)), dir, "doc"));

        String text = Files.readString(dir.resolve("doc.txt"));
        assertTrue(text.contains("BETA"), text);
        assertFalse(text.contains("ALPHA"), text);
        assertFalse(text.contains("GAMMA"), text);
    }

    @Test
    void exportsDocxNativelyWithoutLibreOffice() throws Exception {
        // DOCX is now built in memory as OOXML — no LibreOffice, so this runs everywhere.
        Path src = createPdf("ALPHA", "BETA");
        Path dir = tmp.resolve("docx");

        PdfToTextResult result = PdfTextExporter.execute(
            new PdfToTextOptions(src, TextFormat.DOCX, PageRange.ALL, dir, "doc"));

        assertEquals(dir.resolve("doc.docx"), result.output());
        String xml = documentXml(result.output());
        assertTrue(xml.contains("ALPHA"), "docx should contain the extracted text");
        // Clean OOXML text, not LibreOffice's frame-heavy writer_pdf_import output.
        assertFalse(xml.contains("<wps:"), "docx should be clean text, not frame-heavy");
    }

    @Test
    void docxSplitsIntoMultipleDistinctParagraphs() throws Exception {
        // A heading, a two-line body paragraph, and a second body paragraph separated by a gap.
        Path src = createStructuredPdf();
        Path dir = tmp.resolve("structured");

        PdfTextExporter.execute(
            new PdfToTextOptions(src, TextFormat.DOCX, PageRange.ALL, dir, "doc"));

        String xml = documentXml(dir.resolve("doc.docx"));

        // At least three <w:p> elements: one heading + two body paragraphs (not a single run).
        assertTrue(paragraphCount(xml) >= 3,
            "expected multiple distinct paragraphs, got:\n" + xml);

        // The heading is styled (Word Heading 1), not dumped as body text.
        assertTrue(xml.contains("<w:pStyle w:val=\"Heading1\"/>"),
            "large-font line should become a Heading 1 paragraph:\n" + xml);
        assertTrue(xml.contains("CHAPTER ONE"), xml);

        // The two wrapped lines of paragraph one are re-joined into a single paragraph run,
        // while paragraph two is a separate run — proving real paragraph splitting.
        assertTrue(xml.contains("First body line one continues on line two"),
            "wrapped lines should join into one paragraph:\n" + xml);
        assertTrue(xml.contains("Second paragraph starts here"), xml);
        assertFalse(xml.contains("First body line one continues on line two Second paragraph"),
            "the two paragraphs must not be merged into one:\n" + xml);
    }

    @Test
    void docxInsertsPageBreaksBetweenPages() throws Exception {
        Path src = createPdf("ALPHA", "BETA", "GAMMA");
        Path dir = tmp.resolve("pages");

        PdfTextExporter.execute(
            new PdfToTextOptions(src, TextFormat.DOCX, PageRange.ALL, dir, "doc"));

        String xml = documentXml(dir.resolve("doc.docx"));
        // Two page breaks separate the three pages.
        int breaks = xml.split("<w:br w:type=\"page\"/>", -1).length - 1;
        assertEquals(2, breaks, "expected a page break between each of the 3 pages:\n" + xml);
    }

    private static String documentXml(Path docx) throws IOException {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            var entry = zip.getEntry("word/document.xml");
            assertNotNull(entry, "docx must contain word/document.xml");
            return new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int paragraphCount(String documentXml) {
        return documentXml.split("<w:p>", -1).length - 1;
    }

    private Path createPdf(String... pageTexts) throws IOException {
        Path path = tmp.resolve("src-" + pageTexts.length + "-" + System.nanoTime() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (String t : pageTexts) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.newLineAtOffset(100, 700);
                    cs.showText(t);
                    cs.endText();
                }
            }
            doc.save(path.toFile());
        }
        return path;
    }

    /** One page: a large-font heading, a two-line paragraph, then a gap-separated paragraph. */
    private Path createStructuredPdf() throws IOException {
        Path path = tmp.resolve("structured-" + System.nanoTime() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                drawLine(cs, "CHAPTER ONE", 20, 100, 780);
                drawLine(cs, "First body line one", 12, 100, 740);   // tight spacing -> same
                drawLine(cs, "continues on line two", 12, 100, 725); //   paragraph
                drawLine(cs, "Second paragraph starts here", 12, 100, 680); // gap -> new paragraph
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private void drawLine(PDPageContentStream cs, String text, int size, float x, float y)
            throws IOException {
        cs.beginText();
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
