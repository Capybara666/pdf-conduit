package com.pdfconduit.core.convert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure Markdown → HTML rendering (no LibreOffice needed). The soffice HTML → PDF leg is not
 * exercised here — it is skipped like the other office tests when {@code soffice} is absent.
 */
class MarkdownConverterTest {

    @Test
    void rendersHeadingsListsAndEmphasis() {
        String html = MarkdownConverter.toHtml("""
            # Title
            ## Subtitle

            Some **bold** and *italic* text.

            - one
            - two
            - three

            1. first
            2. second
            """);

        assertTrue(html.contains("<h1>Title</h1>"), html);
        assertTrue(html.contains("<h2>Subtitle</h2>"), html);
        assertTrue(html.contains("<strong>bold</strong>"), html);
        assertTrue(html.contains("<em>italic</em>"), html);
        assertTrue(html.contains("<ul>"), html);
        assertTrue(html.contains("<ol>"), html);
        assertTrue(html.contains("<li>one</li>"), html);
    }

    @Test
    void rendersGfmTablesCodeAndLinks() {
        String html = MarkdownConverter.toHtml("""
            | A | B |
            |---|---|
            | 1 | 2 |

            `inline code` and a [link](https://example.com).

            ```
            block code
            ```
            """);

        assertTrue(html.contains("<table>"), html);
        assertTrue(html.contains("<th>A</th>"), html);
        assertTrue(html.contains("<td>1</td>"), html);
        assertTrue(html.contains("<code>inline code</code>"), html);
        assertTrue(html.contains("<a href=\"https://example.com\">link</a>"), html);
        assertTrue(html.contains("<pre>"), html);
    }

    @Test
    void producesSelfContainedDocumentWithStyle() {
        String html = MarkdownConverter.toHtml("# Hi");
        assertTrue(html.startsWith("<!DOCTYPE html>"), html);
        assertTrue(html.contains("<style>"), "should inline a print stylesheet");
        assertTrue(html.contains("</html>"), html);
    }

    @Test
    void nullAndBlankAreSafe() {
        assertTrue(MarkdownConverter.toHtml(null).contains("<body>"));
        assertTrue(MarkdownConverter.toHtml("").contains("<body>"));
    }

    @Test
    void classifiesMarkdownAsOfficeSoGatingApplies() {
        // Markdown stays OFFICE so pdfconduit.web.office.enabled + the office guards still gate it.
        assertEquals(DocumentConverter.Kind.OFFICE,
            DocumentConverter.classify(java.nio.file.Path.of("notes.md")));
        assertEquals(DocumentConverter.Kind.OFFICE,
            DocumentConverter.classify(java.nio.file.Path.of("notes.markdown")));
        assertTrue(DocumentConverter.isMarkdown("notes.md"));
        assertTrue(DocumentConverter.isMarkdown("notes.MARKDOWN"));
        assertFalse(DocumentConverter.isMarkdown("notes.docx"));
    }
}
