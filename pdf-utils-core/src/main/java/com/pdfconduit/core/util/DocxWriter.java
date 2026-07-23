package com.pdfconduit.core.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A tiny, dependency-free writer for structured Word (.docx) documents.
 *
 * <p>A {@code .docx} is an OOXML package — a ZIP of a handful of XML parts. This writer emits the
 * minimal valid set ({@code [Content_Types].xml}, package + document relationships, a small
 * {@code styles.xml} and {@code word/document.xml}) so PDF Conduit can produce real, styled Word
 * output in memory without shelling out to LibreOffice.
 *
 * <p>Callers hand in an ordered list of {@link Block}s — body paragraphs, headings (outline level
 * 1/2, rendered bold and larger and wired to Word's {@code Heading 1/2} styles so they show up in
 * the navigation outline) and explicit page breaks. Runs are XML-escaped and marked
 * {@code xml:space="preserve"} so leading/trailing spaces survive.
 */
public final class DocxWriter {

    private DocxWriter() {}

    /** A single flow element in the document body. */
    public record Block(String text, int outlineLevel, boolean pageBreak) {
        /** A normal body paragraph. */
        public static Block paragraph(String text) { return new Block(text, 0, false); }

        /** A heading paragraph. {@code level} 1 or 2 selects the Word heading style. */
        public static Block heading(String text, int level) {
            return new Block(text, Math.max(1, Math.min(2, level)), false);
        }

        /** A hard page break (its own empty paragraph carrying a page-break run). */
        public static Block newPage() { return new Block("", 0, true); }

        boolean isHeading() { return outlineLevel >= 1 && !pageBreak; }
    }

    /** Builds the {@code .docx} bytes for {@code blocks}. */
    public static byte[] write(List<Block> blocks) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", CONTENT_TYPES);
            put(zip, "_rels/.rels", PACKAGE_RELS);
            put(zip, "word/_rels/document.xml.rels", DOCUMENT_RELS);
            put(zip, "word/styles.xml", STYLES);
            put(zip, "word/document.xml", document(blocks));
        }
        return bytes.toByteArray();
    }

    private static String document(List<Block> blocks) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
          .append("<w:document xmlns:w=\"").append(W_NS).append("\"><w:body>");
        if (blocks.isEmpty()) {
            sb.append("<w:p/>");
        }
        for (Block b : blocks) {
            if (b.pageBreak()) {
                sb.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            } else if (b.isHeading()) {
                String style = "Heading" + b.outlineLevel();
                int halfPt = b.outlineLevel() == 1 ? 32 : 28;   // 16pt / 14pt
                sb.append("<w:p><w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>")
                  .append("<w:r><w:rPr><w:b/><w:sz w:val=\"").append(halfPt)
                  .append("\"/><w:szCs w:val=\"").append(halfPt).append("\"/></w:rPr>")
                  .append("<w:t xml:space=\"preserve\">").append(escape(b.text()))
                  .append("</w:t></w:r></w:p>");
            } else {
                sb.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                  .append(escape(b.text())).append("</w:t></w:r></w:p>");
            }
        }
        // A minimal section (Letter-ish default page); keeps Word from complaining.
        sb.append("<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/>")
          .append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/>")
          .append("</w:sectPr></w:body></w:document>");
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    // Strip control chars XML 1.0 forbids (except tab); keep everything else.
                    if (c >= 0x20 || c == '\t') out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static final String W_NS =
        "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private static final String CONTENT_TYPES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
        + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
        + "</Types>";

    private static final String PACKAGE_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
        + "</Relationships>";

    private static final String DOCUMENT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
        + "</Relationships>";

    private static final String STYLES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<w:styles xmlns:w=\"" + W_NS + "\">"
        + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/>"
        + "<w:basedOn w:val=\"Normal\"/><w:pPr><w:outlineLvl w:val=\"0\"/></w:pPr>"
        + "<w:rPr><w:b/><w:sz w:val=\"32\"/><w:szCs w:val=\"32\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/>"
        + "<w:basedOn w:val=\"Normal\"/><w:pPr><w:outlineLvl w:val=\"1\"/></w:pPr>"
        + "<w:rPr><w:b/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/></w:rPr></w:style>"
        + "</w:styles>";
}
