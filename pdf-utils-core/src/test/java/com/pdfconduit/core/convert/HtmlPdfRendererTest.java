package com.pdfconduit.core.convert;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ground-truth test for the high-fidelity Markdown/HTML → PDF renderer. Runs entirely in-JVM
 * (OpenHTMLtoPDF + PDFBox) — NO external binary, so it must pass in CI. We render a Markdown
 * sample and then extract text from the produced PDF to prove the heading, a GFM table cell and a
 * Polish (non-Latin) word actually made it into the output.
 */
class HtmlPdfRendererTest {

    private static final String SAMPLE = """
        # Report Heading

        Some **bold** text with a Polish word: zażółć gęślą jaźń.

        - one
        - two

        | Country | Capital |
        |---------|---------|
        | Poland  | Warszawa |

        ```
        int x = 42;
        ```
        """;

    @Test
    void markdownRendersToValidPdfWithHeadingTablePolishText() throws Exception {
        byte[] pdf = HtmlPdfRenderer.markdownToPdf(SAMPLE);

        assertNotNull(pdf);
        assertTrue(pdf.length > 0, "PDF should not be empty");
        // Valid PDF magic bytes.
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));

        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertTrue(doc.getNumberOfPages() >= 1);
            text = new PDFTextStripper().getText(doc);
        }

        // Heading present.
        assertTrue(text.contains("Report Heading"), "heading missing:\n" + text);
        // GFM table cell text present (header + body cells).
        assertTrue(text.contains("Country"), "table header missing:\n" + text);
        assertTrue(text.contains("Warszawa"), "table cell missing:\n" + text);
        // Code-block content present.
        assertTrue(text.contains("int x = 42;"), "code block missing:\n" + text);
        // Polish special characters render as real glyphs (ground truth for the embedded font).
        assertTrue(text.contains("zażółć gęślą jaźń"), "Polish text missing/garbled:\n" + text);
    }

    @Test
    void rawHtmlRendersToValidPdfWithPolishText() throws Exception {
        byte[] pdf = HtmlPdfRenderer.htmlToPdf(
            "<html><body><h1>Nagłówek</h1><p>Cześć, świecie — ąćęłńóśźż.</p></body></html>");

        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        assertTrue(text.contains("Nagłówek"), "heading missing:\n" + text);
        assertTrue(text.contains("ąćęłńóśźż"), "Polish chars missing/garbled:\n" + text);
    }
}
