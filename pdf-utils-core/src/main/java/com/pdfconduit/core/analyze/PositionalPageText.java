package com.pdfconduit.core.analyze;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A position-aware {@link PDFTextStripper} that reconstructs one page's text while
 * remembering, for every character, the {@link TextPosition} (glyph placement) it came
 * from. This lets {@link PiiScanner} turn a detector's character-range match back into
 * on-page bounding boxes.
 *
 * <p>Coordinates come straight from PDFBox's direction-adjusted accessors
 * ({@code getXDirAdj / getYDirAdj / getWidthDirAdj / getHeightDir}) — i.e. displayed-page
 * points with a <b>top-left origin</b> and page rotation already applied, exactly the space
 * {@link com.pdfconduit.core.model.RedactRegion} expects. The reconstructed text is the
 * concatenation of each glyph's Unicode plus the stripper's word / line separators, so the
 * PII detectors see the same content they always did, char-for-char aligned to the position
 * map.
 *
 * <p>Package-private, single-page-at-a-time helper; reused across pages via {@link #load}.
 */
final class PositionalPageText extends PDFTextStripper {

    private StringBuilder sb;
    private List<TextPosition> perChar;   // one entry per char in sb; null for separators

    PositionalPageText() throws IOException {
        super();
    }

    /** Reconstructs the text of {@code page1} (1-based) and captures the per-char positions. */
    void load(PDDocument doc, int page1) throws IOException {
        sb = new StringBuilder();
        perChar = new ArrayList<>();
        setStartPage(page1);
        setEndPage(page1);
        getText(doc);   // drives the write* overrides below; the returned string is unused
    }

    /** The reconstructed page text (aligned index-for-index with the position map). */
    String text() {
        return sb.toString();
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) {
        for (TextPosition tp : textPositions) {
            String u = tp.getUnicode();
            if (u == null || u.isEmpty()) continue;
            for (int i = 0; i < u.length(); i++) {
                sb.append(u.charAt(i));
                perChar.add(tp);
            }
        }
    }

    @Override
    protected void writeWordSeparator() {
        appendSeparator(getWordSeparator());
    }

    @Override
    protected void writeLineSeparator() {
        appendSeparator(getLineSeparator());
    }

    private void appendSeparator(String sep) {
        if (sep == null) return;
        for (int i = 0; i < sep.length(); i++) {
            sb.append(sep.charAt(i));
            perChar.add(null);   // separators have no glyph
        }
    }

    /**
     * Builds the bounding box(es) covering characters {@code [from, to)} of this page's text.
     * Consecutive glyphs on the same text line are unioned into one box; a match that wraps
     * across lines yields one box per line. Separator characters (nulls) are skipped. Returns
     * an empty list if the range covers no positioned glyphs.
     *
     * @param pageIndex0 the zero-based page index to stamp on the produced regions
     */
    List<PiiRegion> regionsFor(int pageIndex0, int from, int to) {
        List<PiiRegion> out = new ArrayList<>();
        Box box = null;
        int end = Math.min(to, perChar.size());
        for (int i = Math.max(0, from); i < end; i++) {
            TextPosition tp = perChar.get(i);
            if (tp == null) continue;
            float x = tp.getXDirAdj();
            float y = tp.getYDirAdj();
            float w = tp.getWidthDirAdj();
            float h = tp.getHeightDir();
            if (w <= 0 || h <= 0) continue;
            if (box != null && box.onDifferentLine(y, h)) {
                out.add(box.toRegion(pageIndex0));
                box = null;
            }
            if (box == null) box = new Box(x, y, w, h);
            else box.add(x, y, w, h);
        }
        if (box != null) out.add(box.toRegion(pageIndex0));
        return out;
    }

    /** Mutable min/max accumulator in displayed-page points, top-left origin. */
    private static final class Box {
        private double left, top, right, bottom;

        Box(double x, double y, double w, double h) {
            left = x;
            top = y;
            right = x + w;
            bottom = y + h;
        }

        void add(double x, double y, double w, double h) {
            left = Math.min(left, x);
            top = Math.min(top, y);
            right = Math.max(right, x + w);
            bottom = Math.max(bottom, y + h);
        }

        /** A vertical shift of more than half the glyph height signals a new line. */
        boolean onDifferentLine(double y, double h) {
            return Math.abs(y - top) > 0.5 * Math.max(h, bottom - top);
        }

        PiiRegion toRegion(int page) {
            return new PiiRegion(page, left, top, right - left, bottom - top);
        }
    }
}
