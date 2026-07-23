package com.pdfconduit.core.convert;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.List;

/**
 * Renders Markdown source into a self-contained HTML document so it can be fed through the same
 * LibreOffice HTML → PDF path the rest of {@link DocumentConverter} already uses. Pure-Java
 * (CommonMark + GFM tables), no external process — the soffice leg only sees the produced HTML.
 *
 * <p>The emitted HTML carries a minimal inline stylesheet so headings, lists, tables, code and
 * blockquotes render with reasonable fidelity once LibreOffice imports it.
 */
public final class MarkdownConverter {

    private MarkdownConverter() {}

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).build();

    /** A small print-oriented stylesheet giving headings/tables/code sane formatting in the PDF. */
    private static final String STYLE = """
        body { font-family: 'Liberation Sans', Arial, sans-serif; font-size: 11pt; line-height: 1.45;
               color: #1a1a1a; margin: 2em; }
        h1, h2, h3, h4, h5, h6 { font-weight: bold; line-height: 1.25; margin: 1.2em 0 0.5em; }
        h1 { font-size: 2em; } h2 { font-size: 1.6em; } h3 { font-size: 1.3em; }
        h4 { font-size: 1.1em; } h5 { font-size: 1em; } h6 { font-size: 0.9em; }
        p { margin: 0.6em 0; }
        ul, ol { margin: 0.6em 0 0.6em 1.6em; }
        li { margin: 0.2em 0; }
        a { color: #0b57d0; }
        code { font-family: 'Liberation Mono', 'Courier New', monospace; font-size: 0.92em;
               background: #f2f2f2; padding: 0.1em 0.3em; }
        pre { font-family: 'Liberation Mono', 'Courier New', monospace; font-size: 0.9em;
              background: #f2f2f2; padding: 0.8em; white-space: pre-wrap; }
        pre code { background: none; padding: 0; }
        blockquote { margin: 0.8em 0; padding: 0.2em 1em; border-left: 3px solid #cccccc;
                     color: #555555; }
        table { border-collapse: collapse; margin: 0.8em 0; }
        th, td { border: 1px solid #999999; padding: 0.35em 0.6em; text-align: left; }
        th { background: #ededed; }
        img { max-width: 100%; }
        hr { border: none; border-top: 1px solid #cccccc; margin: 1.2em 0; }
        """;

    /**
     * Converts Markdown to a complete HTML document (with the print stylesheet inlined). Never
     * throws for content reasons — malformed Markdown simply renders as best-effort HTML.
     */
    public static String toHtml(String markdown) {
        Node document = PARSER.parse(markdown == null ? "" : markdown);
        String body = RENDERER.render(document);
        return "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\"/>\n<style>\n"
            + STYLE + "</style>\n</head>\n<body>\n" + body + "</body>\n</html>\n";
    }
}
