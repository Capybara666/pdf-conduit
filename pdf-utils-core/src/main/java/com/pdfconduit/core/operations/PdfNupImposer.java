package com.pdfconduit.core.operations;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.NupLayout;
import com.pdfconduit.core.model.NupOptions;
import com.pdfconduit.core.model.PdfResult;
import com.pdfconduit.core.util.OutputPaths;
import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.IOException;

/**
 * N-up / booklet imposition: places several source pages onto each output sheet.
 *
 * <p>Grid presets ({@link NupLayout}) tile {@code cols × rows} source pages per sheet
 * in reading order (left→right, top→bottom); each source page is imported as a form
 * XObject (vector-preserving, no rasterisation) and scaled to fit its cell keeping
 * aspect ratio, centred. Booklet mode imposes a saddle-stitch booklet: the page count
 * is padded to a multiple of four and the pages are reordered so a printed, folded and
 * stapled stack reads in order (2-up landscape).
 *
 * <p>Stateless and thread-safe.
 */
public final class PdfNupImposer {

    /** Fraction of each cell left as a margin on every side. */
    private static final float MARGIN = 0.02f;

    private PdfNupImposer() {}

    public static PdfResult execute(NupOptions opts) throws PdfOperationException {
        try (PDDocument src = PdfLoader.load(opts.input())) {
            try (PDDocument out = impose(src, opts.layout(), opts.booklet())) {
                OutputPaths.ensureParentDir(opts.output());
                out.save(opts.output().toFile());
                return new PdfResult(opts.output(), out.getNumberOfPages());
            }
        } catch (IOException e) {
            throw new PdfOperationException("N-up failed: " + e.getMessage(), e);
        }
    }

    /** In-memory variant: impose {@code pdf} and return the new PDF's bytes. */
    public static byte[] executeBytes(byte[] pdf, NupLayout layout, boolean booklet)
            throws PdfOperationException {
        try (PDDocument src = PdfLoader.load(pdf);
             PDDocument out = impose(src, layout, booklet)) {
            return PdfLoader.toBytes(out);
        } catch (IOException e) {
            throw new PdfOperationException("N-up failed: " + e.getMessage(), e);
        }
    }

    /** The shared algorithm: a new document with {@code src}'s pages imposed. */
    static PDDocument impose(PDDocument src, NupLayout layoutIn, boolean booklet) throws IOException {
        NupLayout layout = layoutIn != null ? layoutIn : NupLayout.TWO_UP;
        return booklet ? booklet(src) : grid(src, layout);
    }

    // --- grid presets -----------------------------------------------------

    private static PDDocument grid(PDDocument src, NupLayout layout) throws IOException {
        int cols = layout.cols(), rows = layout.rows(), per = layout.perSheet();
        int total = src.getNumberOfPages();

        PDDocument out = new PDDocument();
        LayerUtility lu = new LayerUtility(out);

        float[] sheet = sheetSize(src.getPage(0).getCropBox(), cols, rows);
        float sheetW = sheet[0], sheetH = sheet[1];
        float cellW = sheetW / cols, cellH = sheetH / rows;

        int sheets = (total + per - 1) / per;
        for (int s = 0; s < sheets; s++) {
            PDPage page = new PDPage(new PDRectangle(sheetW, sheetH));
            out.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(out, page, AppendMode.APPEND, true, true)) {
                for (int cell = 0; cell < per; cell++) {
                    int index = s * per + cell;
                    if (index >= total) break;
                    int col = cell % cols;
                    int rowFromTop = cell / cols;
                    float cellX = col * cellW;
                    float cellY = sheetH - (rowFromTop + 1) * cellH;
                    place(cs, lu, src, index, cellX, cellY, cellW, cellH);
                }
            }
        }
        return out;
    }

    // --- booklet ----------------------------------------------------------

    private static PDDocument booklet(PDDocument src) throws IOException {
        int total = src.getNumberOfPages();
        int padded = ((total + 3) / 4) * 4;   // saddle-stitch needs a multiple of four

        PDDocument out = new PDDocument();
        LayerUtility lu = new LayerUtility(out);

        PDRectangle box = src.getPage(0).getCropBox();
        float longSide = Math.max(box.getWidth(), box.getHeight());
        float shortSide = Math.min(box.getWidth(), box.getHeight());
        float sheetW = longSide, sheetH = shortSide;   // 2-up landscape
        float cellW = sheetW / 2f, cellH = sheetH;

        int faces = padded / 2;
        for (int i = 0; i < faces; i++) {
            // Fold order: outermost pair first, alternating which side the low page sits.
            int left, right;
            if (i % 2 == 0) { left = padded - i; right = i + 1; }
            else            { left = i + 1;      right = padded - i; }

            PDPage page = new PDPage(new PDRectangle(sheetW, sheetH));
            out.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(out, page, AppendMode.APPEND, true, true)) {
                // 1-based page numbers beyond the real count are blank (padding).
                if (left <= total)  place(cs, lu, src, left - 1, 0f, 0f, cellW, cellH);
                if (right <= total) place(cs, lu, src, right - 1, cellW, 0f, cellW, cellH);
            }
        }
        return out;
    }

    // --- placement --------------------------------------------------------

    /** Output sheet size for a {@code cols × rows} grid, auto-oriented from the source page. */
    private static float[] sheetSize(PDRectangle src, int cols, int rows) {
        float pw = src.getWidth(), ph = src.getHeight();
        float longSide = Math.max(pw, ph), shortSide = Math.min(pw, ph);
        if (cols > rows) return new float[]{longSide, shortSide};   // landscape
        if (rows > cols) return new float[]{shortSide, longSide};   // portrait
        return new float[]{pw, ph};                                 // square grid: keep source
    }

    /**
     * Imports source page {@code index} (0-based) as a form and draws it scaled-to-fit,
     * centred, into the cell at {@code (cellX, cellY)} of size {@code cellW × cellH}.
     */
    private static void place(PDPageContentStream cs, LayerUtility lu, PDDocument src, int index,
                              float cellX, float cellY, float cellW, float cellH) throws IOException {
        PDFormXObject form = lu.importPageAsForm(src, src.getPage(index));

        // The form's own matrix bakes in the source page's /Rotate — measure the drawn footprint.
        PDRectangle bb = form.getBBox();
        AffineTransform formMatrix = form.getMatrix().createAffineTransform();
        Rectangle2D drawn = formMatrix.createTransformedShape(
            new Rectangle2D.Float(bb.getLowerLeftX(), bb.getLowerLeftY(), bb.getWidth(), bb.getHeight()))
            .getBounds2D();
        double dw = drawn.getWidth(), dh = drawn.getHeight();
        if (dw <= 0 || dh <= 0) return;

        double availW = cellW * (1 - 2 * MARGIN);
        double availH = cellH * (1 - 2 * MARGIN);
        double scale = Math.min(availW / dw, availH / dh);
        double placedW = dw * scale, placedH = dh * scale;
        double tx = cellX + (cellW - placedW) / 2 - drawn.getMinX() * scale;
        double ty = cellY + (cellH - placedH) / 2 - drawn.getMinY() * scale;

        AffineTransform at = new AffineTransform();
        at.translate(tx, ty);
        at.scale(scale, scale);

        cs.saveGraphicsState();
        cs.transform(new Matrix(at));
        cs.drawForm(form);
        cs.restoreGraphicsState();
    }
}
