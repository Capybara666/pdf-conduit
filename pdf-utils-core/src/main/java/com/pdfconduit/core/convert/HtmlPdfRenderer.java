package com.pdfconduit.core.convert;

import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.extend.FSStreamFactory;
import com.openhtmltopdf.extend.FSUriResolver;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceType;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.pdfconduit.core.exception.PdfOperationException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.Node;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p><b>Security.</b> The HTML is attacker-supplied and reachable unauthenticated (web
 * {@code /api/to-pdf}, {@code /api/pipeline/run}). Rendering is locked to <b>OFFLINE</b>: the only
 * external resources permitted are inline {@code data:} URIs (plus the classpath font embedded via
 * {@code useFont}). Every {@code http}/{@code https}/{@code file}/{@code jar}/{@code ftp}/… fetch is
 * refused, defeating SSRF and local-file disclosure (e.g. {@code <img src="file:///etc/passwd">} or
 * cloud-metadata pulls). This is enforced three ways on the builder — a data-only
 * {@link FSUriResolver}, a data-only {@link com.openhtmltopdf.outputdevice.helper external-resource
 * access-control} predicate, and a denying {@link FSStreamFactory} bound to the external protocols —
 * and again as defense-in-depth in the jsoup cleaning pass, which strips {@code <script>}, external
 * {@code <link>}/{@code <img>}/SVG&nbsp;{@code <image>} and {@code @import}/{@code url(...)} to
 * non-{@code data:} targets, and drops the DOCTYPE so no DTD / external entity is ever resolved (XXE).
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

    // --- offline lockdown (SSRF / local-file defense) ---------------------

    /** True only for inline {@code data:} URIs — the sole external reference the renderer may fetch. */
    private static boolean isDataUri(String uri) {
        if (uri == null) return false;
        String u = uri.stripLeading();
        return u.regionMatches(true, 0, "data:", 0, "data:".length());
    }

    /** Passes {@code data:} URIs through unchanged; refuses to resolve anything else (returns null → skipped). */
    private static final FSUriResolver DATA_ONLY_URI_RESOLVER =
        (baseUri, uri) -> isDataUri(uri) ? uri.strip() : null;

    /** Access-control gate: permit a fetch only when the URI is a {@code data:} URI. */
    private static final BiPredicate<String, ExternalResourceType> DATA_ONLY_ACCESS =
        (uri, type) -> isDataUri(uri);

    /** Stream factory that opens nothing — wired to the external protocols so none can ever be read. */
    private static final FSStreamFactory DENY_STREAM_FACTORY = new FSStreamFactory() {
        @Override public FSStream getUrl(String url) { return null; }
    };

    /** External protocols explicitly denied a stream implementation (belt-and-suspenders). */
    private static final Set<String> BLOCKED_PROTOCOLS =
        Set.of("http", "https", "file", "jar", "ftp", "ftps", "mailto", "ws", "wss");

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
        // Defense-in-depth: strip anything that could pull in an external resource or a DTD/entity
        // before we hand the markup to the (already offline-locked) renderer.
        sanitize(doc);
        doc.outputSettings()
           .syntax(Document.OutputSettings.Syntax.xml)
           .escapeMode(Entities.EscapeMode.xhtml)
           .charset(StandardCharsets.UTF_8)
           .prettyPrint(false);
        return doc.html();
    }

    // --- jsoup sanitisation (defense-in-depth) ----------------------------

    private static final Pattern IMPORT_RULE =
        Pattern.compile("@import\\b[^;]*;?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_URL =
        Pattern.compile("url\\(\\s*(['\"]?)(.*?)\\1\\s*\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Removes every reference that would make the renderer reach out to the network or filesystem,
     * and drops the DOCTYPE (XXE). Inline {@code data:} images are preserved so legitimate embedded
     * artwork still renders.
     */
    private static void sanitize(Document doc) {
        // XXE: remove any DOCTYPE node so no DTD / internal-subset / external entity is referenced.
        for (Node n : new ArrayList<>(doc.childNodes())) {
            if (n instanceof DocumentType) n.remove();
        }
        doc.select("script").remove();

        // External stylesheets: <link rel=stylesheet href="http(s)|file|..."> (keep data:).
        for (Element link : doc.select("link[href]")) {
            if (!isDataUri(link.attr("href"))) link.remove();
        }
        // <base> could rebase relative URIs onto an attacker host — drop it.
        doc.select("base").remove();

        // Raster images and SVG <image> with a non-data source → remove (data: kept).
        for (Element img : doc.select("img")) {
            if (img.hasAttr("src") && !isDataUri(img.attr("src"))) img.remove();
        }
        for (Element image : doc.select("image")) {   // SVG <image>
            String href = image.hasAttr("href") ? image.attr("href")
                        : image.attr("xlink:href");
            if (!href.isEmpty() && !isDataUri(href)) image.remove();
        }

        // Neutralise @import and external url(...) inside <style> blocks and style="" attributes.
        for (Element style : doc.select("style")) {
            String cleaned = stripExternalCss(style.data());
            if (!cleaned.equals(style.data())) {
                style.text("");
                style.appendChild(new org.jsoup.nodes.DataNode(cleaned));
            }
        }
        for (Element el : doc.getAllElements()) {
            if (el.hasAttr("style")) {
                String cleaned = stripExternalCss(el.attr("style"));
                if (!cleaned.equals(el.attr("style"))) el.attr("style", cleaned);
            }
            // Strip any lingering external-URI attributes that could trigger a fetch.
            for (Attribute a : new ArrayList<>(el.attributes().asList())) {
                String key = a.getKey().toLowerCase(java.util.Locale.ROOT);
                if ((key.equals("src") || key.equals("href") || key.equals("xlink:href")
                        || key.equals("background") || key.equals("poster"))
                        && !a.getValue().isEmpty() && !isDataUri(a.getValue())) {
                    // Anchors (<a href>) are fine — they are never fetched; only strip on fetchers.
                    String tag = el.normalName();
                    if (!tag.equals("a")) el.removeAttr(a.getKey());
                }
            }
        }
    }

    /** Drops {@code @import} at-rules and rewrites any non-{@code data:} {@code url(...)} to {@code none}. */
    private static String stripExternalCss(String css) {
        if (css == null || css.isEmpty()) return css == null ? "" : css;
        String out = IMPORT_RULE.matcher(css).replaceAll("");
        Matcher m = CSS_URL.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String target = m.group(2);
            m.appendReplacement(sb, Matcher.quoteReplacement(isDataUri(target) ? m.group() : "none"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Core render: valid XHTML in, PDF bytes out, with the embedded Unicode font registered. */
    private static byte[] renderXhtml(String xhtml) throws PdfOperationException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();

            // OFFLINE lockdown: only data: URIs may ever be fetched; everything external is refused.
            builder.useUriResolver(DATA_ONLY_URI_RESOLVER);
            builder.useExternalResourceAccessControl(
                DATA_ONLY_ACCESS, ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
            builder.useExternalResourceAccessControl(
                DATA_ONLY_ACCESS, ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
            builder.useHttpStreamImplementation(DENY_STREAM_FACTORY);
            builder.useProtocolsStreamImplementation(DENY_STREAM_FACTORY, BLOCKED_PROTOCOLS);

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
