package com.pdfconduit.core.convert;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.pdfconduit.core.exception.PdfOperationException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * High-fidelity Markdown/HTML → PDF using <b>OpenHTMLtoPDF</b> (pure-Java XHTML+CSS renderer with a
 * PDFBox 3 backend — no external binary, so this needs neither LibreOffice nor {@code soffice}).
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #markdownToPdf(String)} — CommonMark + GFM tables → GitHub-styled XHTML
 *       ({@link MarkdownConverter}) → PDF.</li>
 *   <li>{@link #htmlToPdf(String)} — arbitrary HTML cleaned into well-formed XHTML (jsoup) → PDF.
 *       OpenHTMLtoPDF supports CSS 2.1 (plus some CSS 3) but not modern flexbox/JS; callers fall
 *       back to the LibreOffice path if this throws, so nothing regresses.</li>
 * </ul>
 *
 * <p>A bundled Unicode font ({@code DejaVuSans.ttf}) is embedded and registered as the CSS
 * {@code 'DejaVu Sans'} family, so non-Latin text (e.g. Polish ą/ć/ę/ł/…) renders correctly instead
 * of as tofu. Stateless and thread-safe.
 */
public final class HtmlPdfRenderer {

    private HtmlPdfRenderer() {}

    /** Bundled Unicode face, reused from the rest of core. Loaded once. */
    private static final String FONT_RESOURCE = "/fonts/DejaVuSans.ttf";
    private static final String FONT_FAMILY = "DejaVu Sans";
    private static final byte[] FONT_BYTES = loadFont();

    /** Renders GitHub-styled Markdown to a PDF (bytes). */
    public static byte[] markdownToPdf(String markdown) throws PdfOperationException {
        return renderXhtml(cleanToXhtml(MarkdownConverter.toHtml(markdown)));
    }

    /**
     * A low-specificity default stylesheet injected into arbitrary HTML so bare markup inherits the
     * embedded Unicode body font (Polish etc. render as real glyphs, not tofu) and code stays
     * monospace. Any explicit author rule still wins by the cascade.
     */
    private static final String HTML_DEFAULTS =
        "body{font-family:'DejaVu Sans',sans-serif;}"
        + "code,pre,kbd,samp,tt{font-family:'DejaVu Sans Mono',monospace;}";

    /** Renders arbitrary HTML (cleaned to XHTML) to a PDF (bytes). */
    public static byte[] htmlToPdf(String html) throws PdfOperationException {
        Document doc = Jsoup.parse(html == null ? "" : html);
        // Prepend our default font rule so it loses to any author CSS but wins over the UA default.
        doc.head().prependElement("style").attr("type", "text/css").text(HTML_DEFAULTS);
        return renderXhtml(toXhtml(doc));
    }

    /**
     * Parses possibly-messy HTML and re-serialises it as well-formed XHTML (void elements
     * self-closed, entities/charset normalised) so OpenHTMLtoPDF's XML parser accepts it.
     */
    static String cleanToXhtml(String html) {
        return toXhtml(Jsoup.parse(html == null ? "" : html));
    }

    private static String toXhtml(Document doc) {
        doc.outputSettings()
           .syntax(Document.OutputSettings.Syntax.xml)
           .escapeMode(Entities.EscapeMode.xhtml)
           .charset(StandardCharsets.UTF_8)
           .prettyPrint(false);
        return doc.html();
    }

    /** Core render: valid XHTML in, PDF bytes out, with the embedded Unicode font registered. */
    private static byte[] renderXhtml(String xhtml) throws PdfOperationException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            // Embed the bundled Unicode face; a fresh stream per use (the builder may read twice).
            if (FONT_BYTES != null) {
                builder.useFont(() -> new ByteArrayInputStream(FONT_BYTES), FONT_FAMILY);
            }
            builder.withHtmlContent(xhtml, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            // Signal failure so the caller can fall back to the LibreOffice path.
            throw new PdfOperationException("High-fidelity HTML rendering failed.", e);
        }
    }

    private static byte[] loadFont() {
        try (InputStream in = HtmlPdfRenderer.class.getResourceAsStream(FONT_RESOURCE)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
