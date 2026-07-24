package com.pdfconduit.core.convert;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

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

    // --- security regressions --------------------------------------------

    /** 1x1 transparent PNG as a data: URI — a legitimate inline image that MUST keep working. */
    private static final String DATA_PNG =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8"
        + "BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    /**
     * SSRF / local-file defense: HTML referencing {@code http://…} and {@code file:///etc/passwd}
     * resources must render to a valid PDF WITHOUT attempting those fetches. The offline resolver
     * returns null for non-{@code data:} URIs (and the jsoup pass strips the tags), so no socket is
     * opened and no local file is read. A short timeout guarantees we never block on a real fetch,
     * and the marker text below (a /etc/passwd line prefix) must NOT appear in the output.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void externalHttpAndFileResourcesAreBlocked() throws Exception {
        String html = "<html><body>"
            + "<h1>Marker</h1>"
            + "<img src=\"http://127.0.0.1:1/should-not-be-fetched\"/>"
            + "<img src=\"file:///etc/passwd\"/>"
            + "<link rel=\"stylesheet\" href=\"file:///etc/passwd\"/>"
            + "<img src=\"http://169.254.169.254/latest/meta-data/\"/>"
            + "</body></html>";

        byte[] pdf = HtmlPdfRenderer.htmlToPdf(html);   // completes = resolver refused, no fetch
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));

        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        assertTrue(text.contains("Marker"), "page body missing:\n" + text);
        // /etc/passwd contents (if it had been read) would carry "root:" — it must be absent.
        assertFalse(text.contains("root:"), "local file contents leaked into PDF:\n" + text);
    }

    /** Inline {@code data:} images stay allowed — the block is scheme-selective, not blanket. */
    @Test
    void inlineDataUriImageStillRenders() throws Exception {
        byte[] pdf = HtmlPdfRenderer.htmlToPdf(
            "<html><body><p>With image</p><img src=\"" + DATA_PNG + "\"/></body></html>");
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertTrue(doc.getNumberOfPages() >= 1);
            assertTrue(new PDFTextStripper().getText(doc).contains("With image"));
        }
    }

    /**
     * XXE: a DOCTYPE declaring an external SYSTEM entity pointing at {@code file:///etc/passwd} must
     * NOT be expanded — the DOCTYPE is dropped in the jsoup pass, so no DTD / external entity is ever
     * resolved. Render completes and the file contents ("root:") never appear.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void xxeExternalEntityIsNotExpanded() throws Exception {
        String payload =
            "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>"
            + "<html><body><h1>XXE test</h1><p>&xxe;</p></body></html>";

        byte[] pdf = HtmlPdfRenderer.htmlToPdf(payload);
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));

        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        assertTrue(text.contains("XXE test"), "body missing:\n" + text);
        assertFalse(text.contains("root:"), "external entity was expanded (file leaked):\n" + text);
    }
}
