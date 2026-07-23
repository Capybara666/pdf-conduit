package com.pdfconduit.core.convert;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.List;

/**
 * Renders Markdown source into a self-contained, GitHub-styled HTML document. Pure-Java
 * (CommonMark + GFM tables), no external process.
 *
 * <p>The produced document is fed to {@link HtmlPdfRenderer} (OpenHTMLtoPDF, PDFBox 3 backend) for
 * a high-fidelity PDF that looks like a real Markdown rendering engine — proper heading scale,
 * readable body font/line-height, monospace code on a light background, bordered tables with a
 * shaded header row, ruled blockquotes and constrained images. The stylesheet names
 * <b>{@code 'DejaVu Sans'}</b> as the body font so the renderer's embedded Unicode face is used and
 * non-Latin text (Polish ą/ć/ę/ł/ś/ż/ź/ó/ń …) renders correctly.
 */
public final class MarkdownConverter {

    private MarkdownConverter() {}

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).build();

    /**
     * GitHub-flavoured, print-oriented stylesheet (CSS 2.1 only, so OpenHTMLtoPDF renders it
     * faithfully). {@code 'DejaVu Sans'} is the embedded Unicode body face registered by
     * {@link HtmlPdfRenderer}; code falls back to a monospace face for column alignment.
     */
    static final String STYLE = """
        body { font-family: 'DejaVu Sans', sans-serif; font-size: 11pt; line-height: 1.5;
               color: #24292f; margin: 2.2em 2.4em; }
        h1, h2, h3, h4, h5, h6 { font-weight: bold; line-height: 1.25; margin: 1.4em 0 0.6em;
               color: #1f2328; }
        h1 { font-size: 2em; border-bottom: 1px solid #d0d7de; padding-bottom: 0.3em; }
        h2 { font-size: 1.5em; border-bottom: 1px solid #d0d7de; padding-bottom: 0.3em; }
        h3 { font-size: 1.25em; } h4 { font-size: 1em; } h5 { font-size: 0.9em; }
        h6 { font-size: 0.85em; color: #656d76; }
        p { margin: 0.5em 0 0.9em; }
        ul, ol { margin: 0.4em 0 0.9em 0; padding-left: 2em; }
        li { margin: 0.25em 0; }
        a { color: #0969da; text-decoration: none; }
        code { font-family: 'DejaVu Sans Mono', monospace; font-size: 0.88em;
               background: #eff1f3; padding: 0.15em 0.4em; border-radius: 3px; }
        pre { font-family: 'DejaVu Sans Mono', monospace; font-size: 0.86em; line-height: 1.45;
              background: #f6f8fa; padding: 0.9em 1em; border-radius: 5px; white-space: pre-wrap;
              word-wrap: break-word; color: #1f2328; }
        pre code { background: none; padding: 0; font-size: 1em; border-radius: 0; }
        blockquote { margin: 0.8em 0; padding: 0.2em 1em; border-left: 0.25em solid #d0d7de;
                     color: #57606a; }
        table { border-collapse: collapse; margin: 0.6em 0 1em; }
        th, td { border: 1px solid #d0d7de; padding: 0.4em 0.85em; text-align: left; }
        th { background: #f6f8fa; font-weight: bold; }
        tr:nth-child(2n) td { background: #f6f8fa; }
        img { max-width: 100%; }
        hr { border: none; border-top: 2px solid #d0d7de; margin: 1.5em 0; }
        """;

    /**
     * Converts Markdown to just the rendered HTML <em>body</em> fragment (no wrapper). Never throws
     * for content reasons — malformed Markdown simply renders as best-effort HTML.
     */
    public static String toBodyHtml(String markdown) {
        Node document = PARSER.parse(markdown == null ? "" : markdown);
        return RENDERER.render(document);
    }

    /**
     * Converts Markdown to a complete, GitHub-styled HTML document (stylesheet inlined). Never
     * throws for content reasons. The result is valid input for {@link HtmlPdfRenderer}.
     */
    public static String toHtml(String markdown) {
        return "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\"/>\n<style>\n"
            + STYLE + "</style>\n</head>\n<body>\n" + toBodyHtml(markdown) + "</body>\n</html>\n";
    }
}
