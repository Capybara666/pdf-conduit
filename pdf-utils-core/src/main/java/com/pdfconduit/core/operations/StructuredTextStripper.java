package com.pdfconduit.core.operations;

import com.pdfconduit.core.model.PageRange;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link PDFTextStripper} that reconstructs text line-by-line while remembering, for every line,
 * its dominant font size, vertical position and page. {@link PdfTextExporter} uses this to turn a
 * PDF into a structured Word document — grouping wrapped lines into paragraphs on layout gaps,
 * promoting larger-font lines to headings, and inserting page breaks — rather than dumping one
 * undifferentiated blob (which is all a plain {@code getText} + txt→docx conversion can offer).
 *
 * <p>Package-private helper; {@link #collect} runs the extraction and returns the captured lines.
 */
final class StructuredTextStripper extends PDFTextStripper {

    /** One reconstructed text line: its content, dominant font size (pt), baseline y and page. */
    record Line(String text, float fontSize, float y, int page) {}

    private final List<Line> lines = new ArrayList<>();
    private final StringBuilder current = new StringBuilder();
    private float currentMaxFont;
    private float currentY;
    private int currentPage;

    private StructuredTextStripper() throws IOException {
        super();
        setSortByPosition(true);   // sensible reading order for headings/paragraphs
    }

    /** Extracts the lines of {@code doc} (all pages, or the given 1-based pages), in reading order. */
    static List<Line> collect(PDDocument doc, PageRange pages) throws IOException {
        StructuredTextStripper s = new StructuredTextStripper();
        if (pages.isAll()) {
            s.getText(doc);
            s.flush();
        } else {
            for (int page : pages.pageNumbers()) {
                s.setStartPage(page);
                s.setEndPage(page);
                s.getText(doc);
                s.flush();   // don't let a page's trailing line merge into the next
            }
        }
        return s.lines;
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) {
        current.append(text);
        for (TextPosition tp : textPositions) {
            float fs = tp.getFontSizeInPt();
            if (fs <= 0) fs = tp.getFontSize();
            if (fs > currentMaxFont) currentMaxFont = fs;
            currentY = tp.getYDirAdj();
        }
        currentPage = getCurrentPageNo();
    }

    @Override
    protected void writeWordSeparator() {
        current.append(getWordSeparator());
    }

    @Override
    protected void writeLineSeparator() {
        flush();
    }

    @Override
    protected void endPage(PDPage page) throws IOException {
        // PDFBox emits no line separator after a page's final line, so flush here to keep the
        // last line of one page from merging into the first line of the next.
        flush();
        super.endPage(page);
    }

    private void flush() {
        String t = current.toString().strip();
        if (!t.isEmpty()) {
            lines.add(new Line(t, currentMaxFont, currentY, currentPage));
        }
        current.setLength(0);
        currentMaxFont = 0;
    }
}
