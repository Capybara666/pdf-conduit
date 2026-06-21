package com.pdfconduit.core.operations;

import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PdfResult;
import com.pdfconduit.core.model.WatermarkOptions;
import com.pdfconduit.core.util.OutputPaths;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Stamps a text or image watermark onto every page, centred and rotated, at a
 * given opacity. Exactly one of text/image must be supplied. Stateless and
 * thread-safe.
 */
public final class PdfWatermarker {

    private PdfWatermarker() {}

    public static PdfResult execute(WatermarkOptions opts) throws PdfOperationException {
        boolean hasText = opts.text() != null && !opts.text().isBlank();
        boolean hasImage = opts.image() != null;
        if (hasText == hasImage) {
            throw new PdfOperationException("Provide either watermark text or an image, not both.");
        }
        float opacity = (float) Math.max(0, Math.min(1, opts.opacity()));
        double radians = Math.toRadians(opts.rotationDegrees());
        float scale = (float) Math.max(0.05, Math.min(2.0, opts.scale()));

        try (PDDocument doc = PdfLoader.load(opts.input())) {
            PDImageXObject stamp = hasImage ? loadImage(doc, opts) : null;
            PDFont font = hasText ? new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD) : null;

            for (PDPage page : doc.getPages()) {
                PDRectangle box = page.getMediaBox();
                float cx = box.getLowerLeftX() + box.getWidth() / 2f;
                float cy = box.getLowerLeftY() + box.getHeight() / 2f;

                try (PDPageContentStream cs =
                         new PDPageContentStream(doc, page, AppendMode.APPEND, true, true)) {
                    cs.saveGraphicsState();
                    PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                    gs.setNonStrokingAlphaConstant(opacity);
                    gs.setStrokingAlphaConstant(opacity);
                    cs.setGraphicsStateParameters(gs);
                    cs.transform(Matrix.getTranslateInstance(cx, cy));
                    cs.transform(Matrix.getRotateInstance(radians, 0, 0));

                    if (hasText) drawText(cs, font, opts.text(), box, scale);
                    else drawImage(cs, stamp, box, scale);

                    cs.restoreGraphicsState();
                }
            }

            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new PdfResult(opts.output(), doc.getNumberOfPages());
        } catch (IOException e) {
            throw new PdfOperationException("Watermark failed: " + e.getMessage(), e);
        }
    }

    private static void drawText(PDPageContentStream cs, PDFont font, String text,
                                 PDRectangle box, float scale) throws IOException {
        float widthAtSize1;
        try {
            widthAtSize1 = font.getStringWidth(text) / 1000f;
        } catch (IllegalArgumentException e) {
            throw new IOException("Watermark text contains characters this font cannot draw.");
        }
        float fontSize = fontSizeFor(scale, box, widthAtSize1);
        float textWidth = widthAtSize1 * fontSize;

        cs.beginText();
        cs.setFont(font, fontSize);
        cs.setNonStrokingColor(0.6f, 0.6f, 0.6f);   // grey
        cs.newLineAtOffset(-textWidth / 2f, -fontSize / 3f);
        cs.showText(text);
        cs.endText();
    }

    private static void drawImage(PDPageContentStream cs, PDImageXObject img,
                                  PDRectangle box, float scale) throws IOException {
        float[] size = imageSizeFor(scale, box, img.getWidth(), img.getHeight());
        cs.drawImage(img, -size[0] / 2f, -size[1] / 2f, size[0], size[1]);
    }

    /** Font size so the text spans {@code scale} × page width, capped to fit the page height. */
    static float fontSizeFor(float scale, PDRectangle box, float widthAtSize1) {
        float fontSize = widthAtSize1 > 0 ? (box.getWidth() * scale) / widthAtSize1 : 48f;
        return Math.min(fontSize, box.getHeight() * 0.9f);
    }

    /** Drawn {@code [width, height]} so the image spans {@code scale} × page width, capped to the page height. */
    static float[] imageSizeFor(float scale, PDRectangle box, float imgW, float imgH) {
        float drawW = box.getWidth() * scale;
        float drawH = drawW * imgH / imgW;
        float maxH = box.getHeight() * 0.95f;
        if (drawH > maxH) {
            drawH = maxH;
            drawW = drawH * imgW / imgH;
        }
        return new float[]{drawW, drawH};
    }

    private static PDImageXObject loadImage(PDDocument doc, WatermarkOptions opts) throws IOException {
        BufferedImage bi = ImageIO.read(opts.image().toFile());
        if (bi == null) throw new IOException("Cannot read watermark image: " + opts.image().getFileName());
        return LosslessFactory.createFromImage(doc, bi);
    }
}
