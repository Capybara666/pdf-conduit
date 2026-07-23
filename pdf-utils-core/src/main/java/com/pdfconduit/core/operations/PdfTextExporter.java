package com.pdfconduit.core.operations;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PdfToTextOptions;
import com.pdfconduit.core.model.PdfToTextResult;
import com.pdfconduit.core.model.TextFormat;
import com.pdfconduit.core.operations.StructuredTextStripper.Line;
import com.pdfconduit.core.util.DocxWriter;
import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports a PDF's text content as TXT or DOCX.
 *
 * <p>TXT is the raw {@link PDFTextStripper} extraction (honouring the page range). DOCX is built
 * directly as an OOXML package by {@link DocxWriter} — <b>no LibreOffice</b> and no new dependency —
 * and, unlike a flat txt→docx dump, carries real structure:
 * <ul>
 *   <li><b>Paragraphs</b> — wrapped lines are re-joined and split into paragraphs on vertical
 *       layout gaps, not one blob or one paragraph per visual line.</li>
 *   <li><b>Headings</b> — lines whose dominant font size stands out above the body text become
 *       bold, larger {@code Heading 1/2} paragraphs (so they appear in Word's outline).</li>
 *   <li><b>Page breaks</b> — a hard page break is inserted between source pages.</li>
 * </ul>
 */
public final class PdfTextExporter {

    private PdfTextExporter() {}

    public static PdfToTextResult execute(PdfToTextOptions opts) throws PdfOperationException {
        Path out = opts.outputDir().resolve(opts.baseName() + "." + opts.format().extension());
        try {
            Files.createDirectories(opts.outputDir());
        } catch (IOException e) {
            throw new PdfOperationException("Cannot create output folder: " + e.getMessage(), e);
        }

        try (PDDocument doc = PdfLoader.load(opts.input())) {
            byte[] data = render(doc, opts.format(), opts.pages());
            Files.write(out, data);
        } catch (IOException e) {
            throw new PdfOperationException("Text export failed: " + e.getMessage(), e);
        }
        return new PdfToTextResult(out);
    }

    /**
     * In-memory variant: extract the text of {@code pdf} (honouring {@code pages}) as a String.
     * Pure in-memory (PDFBox {@link PDFTextStripper}); no disk touched.
     */
    public static String extractTextBytes(byte[] pdf, PageRange pages) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            return extractText(doc, pages);
        } catch (IOException e) {
            throw new PdfOperationException("Text export failed: " + e.getMessage(), e);
        }
    }

    /**
     * In-memory variant: export the text of {@code pdf} in {@code format} and return the bytes.
     * Both TXT and DOCX are produced purely in memory — no disk, no external process.
     */
    public static byte[] toTextBytes(byte[] pdf, TextFormat format, PageRange pages)
            throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            return render(doc, format, pages);
        } catch (IOException e) {
            throw new PdfOperationException("Text export failed: " + e.getMessage(), e);
        }
    }

    /** Produce the bytes for {@code format} from an already-loaded document. */
    private static byte[] render(PDDocument doc, TextFormat format, PageRange pages)
            throws IOException {
        if (format == TextFormat.TXT) {
            return extractText(doc, pages).getBytes(StandardCharsets.UTF_8);
        }
        return DocxWriter.write(structure(StructuredTextStripper.collect(doc, pages)));
    }

    /** The shared plain-text extraction: all pages, or the given 1-based pages, of {@code doc}. */
    private static String extractText(PDDocument doc, PageRange pages) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        if (pages.isAll()) {
            return stripper.getText(doc);
        }
        StringBuilder sb = new StringBuilder();
        for (int pageNum : pages.pageNumbers()) {
            stripper.setStartPage(pageNum);
            stripper.setEndPage(pageNum);
            sb.append(stripper.getText(doc));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ structuring

    /**
     * Turns captured lines into a flow of DOCX blocks: headings (larger-than-body font),
     * paragraphs (wrapped lines re-joined, split on vertical gaps) and page breaks (between pages).
     */
    static List<DocxWriter.Block> structure(List<Line> lines) {
        List<DocxWriter.Block> blocks = new ArrayList<>();
        if (lines.isEmpty()) return blocks;

        float bodyFont = bodyFontSize(lines);

        StringBuilder para = new StringBuilder();
        float prevY = 0;
        int prevPage = -1;
        boolean prevHeading = false;

        for (Line line : lines) {
            boolean pageChanged = prevPage != -1 && line.page() != prevPage;
            boolean heading = isHeading(line, bodyFont);
            boolean gap = !pageChanged && prevPage != -1
                && (line.y() - prevY) > 1.8f * Math.max(bodyFont, line.fontSize());

            if (pageChanged) {
                flush(blocks, para);
                blocks.add(DocxWriter.Block.newPage());
            }

            if (heading) {
                flush(blocks, para);
                int level = line.fontSize() >= bodyFont * 1.5f ? 1 : 2;
                blocks.add(DocxWriter.Block.heading(line.text(), level));
            } else if (para.length() == 0 || prevHeading || gap || pageChanged) {
                flush(blocks, para);
                para.append(line.text());
            } else {
                para.append(' ').append(line.text());
            }

            prevY = line.y();
            prevPage = line.page();
            prevHeading = heading;
        }
        flush(blocks, para);
        return blocks;
    }

    private static void flush(List<DocxWriter.Block> blocks, StringBuilder para) {
        if (para.length() > 0) {
            blocks.add(DocxWriter.Block.paragraph(para.toString()));
            para.setLength(0);
        }
    }

    /** A line is a heading when its font is clearly larger than the body and the line is short. */
    private static boolean isHeading(Line line, float bodyFont) {
        return line.fontSize() >= bodyFont + 1.0f
            && line.fontSize() >= bodyFont * 1.12f
            && line.text().length() <= 120;
    }

    /** The most common (rounded) line font size — the body text size that headings rise above. */
    private static float bodyFontSize(List<Line> lines) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Line line : lines) {
            int rounded = Math.round(line.fontSize());
            if (rounded > 0) counts.merge(rounded, 1, Integer::sum);
        }
        int best = 0, bestCount = -1;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) { bestCount = e.getValue(); best = e.getKey(); }
        }
        return best > 0 ? best : 12f;
    }
}
