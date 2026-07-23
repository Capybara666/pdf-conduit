package com.pdfconduit.core.operations;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageMarksOptions;
import com.pdfconduit.core.model.PdfResult;
import com.pdfconduit.core.util.OutputPaths;
import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Stamps page numbers and/or header &amp; footer text onto every page. Six text slots are drawn —
 * a header (top) and footer (bottom), each with left / center / right — with token substitution
 * ({@code {page}}, {@code {n}}, {@code {pages}}, {@code {date}}) and optional Bates-style numbering.
 *
 * <p>Each mark is written through an appended {@link PDPageContentStream} so the existing page
 * content is untouched. A per-page transform maps a "visual" coordinate frame (origin at the
 * visible bottom-left, upright axes) onto the page's user space, so margins and text orientation
 * stay correct on rotated pages and pages whose crop box does not start at the origin. Stateless
 * and thread-safe.
 */
public final class PdfPageMarker {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Bundled Unicode font (covers Latin + Polish diacritics), embedded/subset once per document. */
    private static final String FONT_RESOURCE = "/fonts/DejaVuSans.ttf";

    private PdfPageMarker() {}

    public static PdfResult execute(PageMarksOptions opts) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(opts.input())) {
            apply(doc, opts.headerLeft(), opts.headerCenter(), opts.headerRight(),
                opts.footerLeft(), opts.footerCenter(), opts.footerRight(),
                opts.fontSize(), opts.margin(), opts.skipFirstPage(),
                opts.startNumber(), opts.numberPrefix());
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new PdfResult(opts.output(), doc.getNumberOfPages());
        } catch (IOException e) {
            throw new PdfOperationException("Page marks failed: " + e.getMessage(), e);
        }
    }

    /** In-memory variant: stamp marks onto {@code pdf} and return the new PDF bytes. */
    public static byte[] executeBytes(byte[] pdf,
                                      String headerLeft, String headerCenter, String headerRight,
                                      String footerLeft, String footerCenter, String footerRight,
                                      float fontSize, float margin, boolean skipFirstPage,
                                      int startNumber, String numberPrefix)
            throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            apply(doc, headerLeft, headerCenter, headerRight, footerLeft, footerCenter, footerRight,
                fontSize, margin, skipFirstPage, startNumber, numberPrefix);
            return PdfLoader.toBytes(doc);
        } catch (IOException e) {
            throw new PdfOperationException("Page marks failed: " + e.getMessage(), e);
        }
    }

    /** The shared algorithm: stamp the six slots onto every (non-skipped) page of {@code doc}. */
    static void apply(PDDocument doc,
                      String headerLeft, String headerCenter, String headerRight,
                      String footerLeft, String footerCenter, String footerRight,
                      float fontSizeIn, float marginIn, boolean skipFirstPage,
                      int startNumber, String numberPrefix)
            throws PdfOperationException, IOException {

        boolean any = notBlank(headerLeft) || notBlank(headerCenter) || notBlank(headerRight)
            || notBlank(footerLeft) || notBlank(footerCenter) || notBlank(footerRight);
        if (!any) {
            throw new PdfOperationException(
                "Provide at least one header or footer slot (page numbers go in a slot, e.g. {page}).");
        }

        float fontSize = fontSizeIn > 0 ? fontSizeIn : 10f;
        float margin = marginIn >= 0 ? marginIn : 36f;
        PDFont font = loadFont(doc);
        int totalPages = doc.getNumberOfPages();
        String date = LocalDate.now().format(DATE);
        String prefix = numberPrefix == null ? "" : numberPrefix.trim();

        int pageIndex = 0;
        for (PDPage page : doc.getPages()) {
            int index = pageIndex++;
            if (skipFirstPage && index == 0) continue;

            String number = renderNumber(prefix, startNumber + index);
            // {pages} is the plain total page count — never Bates-prefixed or zero-padded.
            String total = Integer.toString(totalPages);

            PDRectangle box = page.getCropBox();
            int rot = ((page.getRotation() % 360) + 360) % 360;
            boolean quarter = rot == 90 || rot == 270;
            float w = box.getWidth(), h = box.getHeight();
            float visibleW = quarter ? h : w;
            float visibleH = quarter ? w : h;
            Matrix frame = visualFrame(rot, box.getLowerLeftX(), box.getLowerLeftY(), w, h);

            String hl = resolve(headerLeft, number, total, date);
            String hc = resolve(headerCenter, number, total, date);
            String hr = resolve(headerRight, number, total, date);
            String fl = resolve(footerLeft, number, total, date);
            String fc = resolve(footerCenter, number, total, date);
            String fr = resolve(footerRight, number, total, date);

            float headerBaseline = visibleH - margin - fontSize;
            float footerBaseline = margin;

            try (PDPageContentStream cs =
                     new PDPageContentStream(doc, page, AppendMode.APPEND, true, true)) {
                cs.saveGraphicsState();
                cs.transform(frame);
                cs.setNonStrokingColor(0f, 0f, 0f);
                drawSlot(cs, font, fontSize, hl, Align.LEFT, visibleW, margin, headerBaseline);
                drawSlot(cs, font, fontSize, hc, Align.CENTER, visibleW, margin, headerBaseline);
                drawSlot(cs, font, fontSize, hr, Align.RIGHT, visibleW, margin, headerBaseline);
                drawSlot(cs, font, fontSize, fl, Align.LEFT, visibleW, margin, footerBaseline);
                drawSlot(cs, font, fontSize, fc, Align.CENTER, visibleW, margin, footerBaseline);
                drawSlot(cs, font, fontSize, fr, Align.RIGHT, visibleW, margin, footerBaseline);
                cs.restoreGraphicsState();
            }
        }
    }

    private enum Align { LEFT, CENTER, RIGHT }

    /** Draws one slot's text (nothing when blank), horizontally in the visual frame. */
    private static void drawSlot(PDPageContentStream cs, PDFont font, float fontSize, String text,
                                 Align align, float visibleW, float margin, float baseline)
            throws IOException {
        if (text == null || text.isEmpty()) return;
        float textWidth = font.getStringWidth(text) / 1000f * fontSize;
        float x = switch (align) {
            case LEFT -> margin;
            case CENTER -> (visibleW - textWidth) / 2f;
            case RIGHT -> visibleW - margin - textWidth;
        };
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(x, baseline);
        cs.showText(text);
        cs.endText();
    }

    /**
     * The transform mapping a visual coordinate frame (origin at the visible bottom-left, x right,
     * y up, sized to the <em>rotated</em> page) onto page user space. Applied once per page so all
     * slot maths can be done in upright visible coordinates regardless of {@code /Rotate}.
     */
    static Matrix visualFrame(int rot, float llx, float lly, float w, float h) {
        return switch (rot) {
            case 90  -> new Matrix(0, 1, -1, 0, llx + w, lly);
            case 180 -> new Matrix(-1, 0, 0, -1, llx + w, lly + h);
            case 270 -> new Matrix(0, -1, 1, 0, llx, lly + h);
            default  -> new Matrix(1, 0, 0, 1, llx, lly);
        };
    }

    /** Substitutes the supported tokens; returns "" for a null/blank slot. */
    static String resolve(String slot, String number, String total, String date) {
        if (slot == null || slot.isBlank()) return "";
        return slot.replace("{page}", number)
                   .replace("{n}", number)
                   .replace("{pages}", total)
                   .replace("{date}", date);
    }

    /** Plain page number, or Bates-style ({@code prefix + zero-padded to 6 digits}) when prefixed. */
    static String renderNumber(String prefix, int value) {
        if (prefix == null || prefix.isEmpty()) return Integer.toString(value);
        return prefix + String.format("%06d", value);
    }

    /**
     * Loads the bundled Unicode TrueType font, embedded as a subset once per document, so page
     * marks render real Unicode text (e.g. Polish {@code ąćęłńóśźż}) instead of {@code ?} fallbacks.
     */
    private static PDFont loadFont(PDDocument doc) throws PdfOperationException {
        try (InputStream in = PdfPageMarker.class.getResourceAsStream(FONT_RESOURCE)) {
            if (in == null) {
                throw new PdfOperationException("Page-marks font resource missing: " + FONT_RESOURCE);
            }
            return PDType0Font.load(doc, in, true);   // embed a subset
        } catch (IOException e) {
            throw new PdfOperationException("Cannot load page-marks font: " + e.getMessage(), e);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
