package org.example.core.operations;

import org.apache.pdfbox.Loader;
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
import org.example.core.exception.PdfOperationException;
import org.example.core.model.PdfResult;
import org.example.core.model.WatermarkOptions;
import org.example.core.util.OutputPaths;

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

        try (PDDocument doc = Loader.loadPDF(opts.input().toFile())) {
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

                    if (hasText) drawText(cs, font, opts.text(), box);
                    else drawImage(cs, stamp, box);

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

    private static void drawText(PDPageContentStream cs, PDFont font, String text, PDRectangle box)
            throws IOException {
        float widthAtSize1;
        try {
            widthAtSize1 = font.getStringWidth(text) / 1000f;
        } catch (IllegalArgumentException e) {
            throw new IOException("Watermark text contains characters this font cannot draw.");
        }
        float fontSize = widthAtSize1 > 0 ? (box.getWidth() * 0.7f) / widthAtSize1 : 48f;
        fontSize = Math.min(fontSize, box.getHeight() * 0.5f);
        float textWidth = widthAtSize1 * fontSize;

        cs.beginText();
        cs.setFont(font, fontSize);
        cs.setNonStrokingColor(0.6f, 0.6f, 0.6f);   // grey
        cs.newLineAtOffset(-textWidth / 2f, -fontSize / 3f);
        cs.showText(text);
        cs.endText();
    }

    private static void drawImage(PDPageContentStream cs, PDImageXObject img, PDRectangle box)
            throws IOException {
        float drawW = box.getWidth() * 0.4f;
        float drawH = drawW * img.getHeight() / img.getWidth();
        float maxH = box.getHeight() * 0.6f;
        if (drawH > maxH) {
            drawH = maxH;
            drawW = drawH * img.getWidth() / img.getHeight();
        }
        cs.drawImage(img, -drawW / 2f, -drawH / 2f, drawW, drawH);
    }

    private static PDImageXObject loadImage(PDDocument doc, WatermarkOptions opts) throws IOException {
        BufferedImage bi = ImageIO.read(opts.image().toFile());
        if (bi == null) throw new IOException("Cannot read watermark image: " + opts.image().getFileName());
        return LosslessFactory.createFromImage(doc, bi);
    }
}
