package com.pdfconduit.core.operations;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.CropOptions;
import com.pdfconduit.core.model.PdfResult;
import com.pdfconduit.core.util.OutputPaths;
import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.io.IOException;

/**
 * Crops every page to a smaller box by trimming a margin off each edge. The crop is applied
 * to each page's <em>crop box</em> (relative to its current crop box, which defaults to the
 * media box), so it visually removes borders/whitespace without discarding the page content —
 * the crop is reversible. Margins are in points, or millimetres when requested. Stateless and
 * thread-safe; both a {@code Path}-based and an in-memory {@code byte[]} variant are provided.
 */
public final class PdfCropper {

    /** Points per millimetre (72 pt / 25.4 mm). */
    private static final double MM_TO_PT = 72.0 / 25.4;

    /** Never let a page collapse below this width/height (points). */
    private static final float MIN_SIDE = 1f;

    private PdfCropper() {}

    public static PdfResult execute(CropOptions opts) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(opts.input())) {
            applyCrop(doc, opts.top(), opts.right(), opts.bottom(), opts.left(), opts.millimetres());
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new PdfResult(opts.output(), doc.getNumberOfPages());
        } catch (IOException e) {
            throw new PdfOperationException("Crop failed: " + e.getMessage(), e);
        }
    }

    /** In-memory variant: crop every page of {@code pdf} and return the new PDF bytes. */
    public static byte[] executeBytes(byte[] pdf, double top, double right, double bottom,
                                      double left, boolean millimetres) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            applyCrop(doc, top, right, bottom, left, millimetres);
            return PdfLoader.toBytes(doc);
        } catch (IOException e) {
            throw new PdfOperationException("Crop failed: " + e.getMessage(), e);
        }
    }

    /** The shared algorithm: shrink each page's crop box by the (non-negative) margins. */
    static void applyCrop(PDDocument doc, double top, double right, double bottom, double left,
                          boolean millimetres) {
        double factor = millimetres ? MM_TO_PT : 1.0;
        float t = (float) (Math.max(0, top) * factor);
        float r = (float) (Math.max(0, right) * factor);
        float b = (float) (Math.max(0, bottom) * factor);
        float l = (float) (Math.max(0, left) * factor);

        for (PDPage page : doc.getPages()) {
            PDRectangle box = page.getCropBox();   // falls back to the media box when unset
            float llx = box.getLowerLeftX() + l;
            float lly = box.getLowerLeftY() + b;
            float urx = box.getUpperRightX() - r;
            float ury = box.getUpperRightY() - t;

            // Clamp so an over-large margin never inverts the box; keep a centred sliver instead.
            if (urx - llx < MIN_SIDE) {
                float cx = (box.getLowerLeftX() + box.getUpperRightX()) / 2f;
                llx = cx - MIN_SIDE / 2f;
                urx = cx + MIN_SIDE / 2f;
            }
            if (ury - lly < MIN_SIDE) {
                float cy = (box.getLowerLeftY() + box.getUpperRightY()) / 2f;
                lly = cy - MIN_SIDE / 2f;
                ury = cy + MIN_SIDE / 2f;
            }

            PDRectangle cropped = new PDRectangle();
            cropped.setLowerLeftX(llx);
            cropped.setLowerLeftY(lly);
            cropped.setUpperRightX(urx);
            cropped.setUpperRightY(ury);
            page.setCropBox(cropped);
        }
    }
}
