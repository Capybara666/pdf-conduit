package com.pdfconduit.core.analyze;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
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
            float w = tp.getWidthDirAdj();
            if (w <= 0) continue;
            Glyph g = glyphBox(tp);
            if (g.bottom() <= g.top()) continue;
            if (box != null && box.onDifferentLine(g.baseline(), g.height())) {
                out.add(box.toRegion(pageIndex0));
                box = null;
            }
            if (box == null) box = new Box(x, w, g);
            else box.add(x, w, g);
        }
        if (box != null) out.add(box.toRegion(pageIndex0));
        return out;
    }

    /**
     * The vertical extent, in displayed-page points with a <b>top-left origin</b>, that a
     * glyph's visible ink actually occupies.
     *
     * <p>PDFBox's {@link TextPosition#getYDirAdj()} is <em>not</em> the top of the glyph — for
     * an un-rotated page it is the <b>text baseline</b> measured from the page top (verified
     * empirically: 12&nbsp;pt text on baseline PDF-y&nbsp;760 of an 841.89&nbsp;pt A4 page reports
     * {@code yDirAdj = 81.89 = 841.89 − 760}). Glyph bodies sit <em>above</em> that baseline
     * (caps/ascenders) with descenders dropping just below it, so a box built as
     * {@code [yDirAdj, yDirAdj + height]} lands <em>under</em> the text — it underlines rather
     * than covers it, leaking the value on a "redacted" page.
     *
     * <p>The true body therefore runs {@code top = baseline − ascent} … {@code bottom =
     * baseline + descent}, where ascent/descent come from the font descriptor (glyph-space
     * units per 1000&nbsp;em, scaled by the displayed font size). A small pad is added so
     * anti-aliased glyph edges are fully covered — redaction must never leave a stray pixel of
     * the value visible. Metrics are clamped to sane fractions of the font size so a pathological
     * font's bounding box cannot black out neighbouring lines.
     */
    private Glyph glyphBox(TextPosition tp) {
        double baseline = tp.getYDirAdj();
        double fontSize = tp.getFontSizeInPt();
        if (fontSize <= 0) fontSize = tp.getFontSize();
        if (fontSize <= 0) fontSize = tp.getHeightDir();   // last-ditch positive scale

        double ascent = 0, descent = 0;
        PDFontDescriptor fd = null;
        try {
            if (tp.getFont() != null) fd = tp.getFont().getFontDescriptor();
        } catch (RuntimeException ignored) {
            fd = null;   // some malformed embedded fonts throw while resolving the descriptor
        }
        if (fd != null && fd.getAscent() > 0) {
            ascent = fd.getAscent() / 1000.0 * fontSize;
            descent = Math.abs(fd.getDescent()) / 1000.0 * fontSize;
        }
        // Fallbacks / floors when the descriptor is missing or degenerate.
        if (ascent <= 0) ascent = Math.max(tp.getHeightDir(), fontSize * 0.75);
        if (descent <= 0) descent = fontSize * 0.25;

        // Clamp so a bad font can't over-cover into adjacent lines, then pad for AA edges.
        ascent = Math.min(ascent, fontSize * 1.1) + fontSize * 0.08;
        descent = Math.min(descent, fontSize * 0.5) + fontSize * 0.05;

        return new Glyph(baseline, baseline - ascent, baseline + descent);
    }

    /** One glyph's vertical geometry in top-left points: its baseline plus ink top/bottom. */
    private record Glyph(double baseline, double top, double bottom) {
        double height() { return bottom - top; }
    }

    /** Mutable min/max accumulator in displayed-page points, top-left origin. */
    private static final class Box {
        private double left, top, right, bottom;
        private final double baseline;   // reference baseline for line-break detection

        Box(double x, double w, Glyph g) {
            left = x;
            right = x + w;
            top = g.top();
            bottom = g.bottom();
            baseline = g.baseline();
        }

        void add(double x, double w, Glyph g) {
            left = Math.min(left, x);
            right = Math.max(right, x + w);
            top = Math.min(top, g.top());
            bottom = Math.max(bottom, g.bottom());
        }

        /**
         * A baseline shift of more than half the glyph height signals a new text line.
         * Compares baselines (shared by all glyphs on a line) rather than box tops, which
         * now track ink extent and would otherwise drift as a line mixes tall and short glyphs.
         */
        boolean onDifferentLine(double glyphBaseline, double glyphHeight) {
            return Math.abs(glyphBaseline - baseline) > 0.5 * Math.max(glyphHeight, bottom - top);
        }

        PiiRegion toRegion(int page) {
            return new PiiRegion(page, left, top, right - left, bottom - top);
        }
    }
}
