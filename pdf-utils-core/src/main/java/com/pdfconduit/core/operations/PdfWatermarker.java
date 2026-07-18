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
import com.pdfconduit.core.model.WatermarkOptions.Layout;
import com.pdfconduit.core.model.WatermarkOptions.Position;
import com.pdfconduit.core.util.OutputPaths;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stamps a text or image watermark onto every page at a given opacity and rotation.
 * The mark can be placed once ({@link Layout#SINGLE}, at a chosen corner/centre),
 * tiled across the whole page ({@link Layout#TILE}) or repeated along a diagonal band
 * ({@link Layout#DIAGONAL}). Exactly one of text/image must be supplied. Stateless and
 * thread-safe.
 */
public final class PdfWatermarker {

    /** Hard cap on stamps per page for tiled/diagonal layouts (guards against tiny scales). */
    private static final int MAX_STAMPS_PER_PAGE = 4000;

    private PdfWatermarker() {}

    public static PdfResult execute(WatermarkOptions opts) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(opts.input())) {
            BufferedImage image = opts.image() != null ? readImage(opts.image()) : null;
            applyWatermark(doc, opts.text(), image, opts.opacity(),
                opts.rotationDegrees(), opts.scale(), opts.layout(), opts.position(), opts.color());
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new PdfResult(opts.output(), doc.getNumberOfPages());
        } catch (IOException e) {
            throw new PdfOperationException("Watermark failed: " + e.getMessage(), e);
        }
    }

    /**
     * In-memory variant (backwards-compatible): a single, centred, default-grey watermark.
     * Provide exactly one of {@code text} / {@code imageBytes}.
     */
    public static byte[] executeBytes(byte[] pdf, String text, byte[] imageBytes, double opacity,
                                      double rotationDegrees, double scale) throws PdfOperationException {
        return executeBytes(pdf, text, imageBytes, opacity, rotationDegrees, scale,
            Layout.SINGLE, Position.CENTER, null);
    }

    /**
     * In-memory variant with full layout/position/tint control: stamp a text or image
     * watermark onto every page of {@code pdf} and return the new PDF bytes.
     */
    public static byte[] executeBytes(byte[] pdf, String text, byte[] imageBytes, double opacity,
                                      double rotationDegrees, double scale, Layout layout,
                                      Position position, String color) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            BufferedImage image = imageBytes != null ? readImage(imageBytes) : null;
            applyWatermark(doc, text, image, opacity, rotationDegrees, scale, layout, position, color);
            return PdfLoader.toBytes(doc);
        } catch (IOException e) {
            throw new PdfOperationException("Watermark failed: " + e.getMessage(), e);
        }
    }

    /** The shared algorithm: stamp {@code text} or {@code image} onto every page of {@code doc}. */
    static void applyWatermark(PDDocument doc, String text, BufferedImage image, double opacityIn,
                               double rotationDegrees, double scaleIn, Layout layoutIn,
                               Position positionIn, String colorHex)
            throws PdfOperationException, IOException {
        boolean hasText = text != null && !text.isBlank();
        boolean hasImage = image != null;
        if (hasText == hasImage) {
            throw new PdfOperationException("Provide either watermark text or an image, not both.");
        }
        float opacity = (float) Math.max(0, Math.min(1, opacityIn));
        double radians = Math.toRadians(rotationDegrees);
        float scale = (float) Math.max(0.05, Math.min(2.0, scaleIn));
        Layout layout = layoutIn != null ? layoutIn : Layout.SINGLE;
        Position position = positionIn != null ? positionIn : Position.CENTER;
        float[] rgb = parseColor(colorHex);

        PDImageXObject stamp = hasImage ? LosslessFactory.createFromImage(doc, image) : null;
        PDFont font = hasText ? new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD) : null;

        for (PDPage page : doc.getPages()) {
            PDRectangle box = page.getMediaBox();

            // Compute the stamp's drawn footprint once per page.
            float stampW, stampH, fontSize = 0f, textWidth = 0f;
            if (hasText) {
                float widthAtSize1;
                try {
                    widthAtSize1 = font.getStringWidth(text) / 1000f;
                } catch (IllegalArgumentException e) {
                    throw new IOException("Watermark text contains characters this font cannot draw.");
                }
                fontSize = fontSizeFor(scale, box, widthAtSize1);
                textWidth = widthAtSize1 * fontSize;
                stampW = textWidth;
                stampH = fontSize;
            } else {
                float[] size = imageSizeFor(scale, box, stamp.getWidth(), stamp.getHeight());
                stampW = size[0];
                stampH = size[1];
            }

            List<float[]> anchors = anchorsFor(layout, position, box, stampW, stampH, radians);

            try (PDPageContentStream cs =
                     new PDPageContentStream(doc, page, AppendMode.APPEND, true, true)) {
                PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                gs.setNonStrokingAlphaConstant(opacity);
                gs.setStrokingAlphaConstant(opacity);
                cs.setGraphicsStateParameters(gs);

                for (float[] a : anchors) {
                    cs.saveGraphicsState();
                    cs.transform(Matrix.getTranslateInstance(a[0], a[1]));
                    cs.transform(Matrix.getRotateInstance(radians, 0, 0));
                    if (hasText) drawText(cs, font, text, fontSize, textWidth, rgb);
                    else drawImage(cs, stamp, stampW, stampH);
                    cs.restoreGraphicsState();
                }
            }
        }
    }

    /** Anchor points (page coords) where the stamp centre is placed, per layout. */
    static List<float[]> anchorsFor(Layout layout, Position position, PDRectangle box,
                                    float stampW, float stampH, double radians) {
        float llx = box.getLowerLeftX(), lly = box.getLowerLeftY();
        float w = box.getWidth(), h = box.getHeight();
        float cx = llx + w / 2f, cy = lly + h / 2f;

        List<float[]> anchors = new ArrayList<>();
        switch (layout) {
            case TILE -> {
                float gapX = Math.max(stampW * 0.4f, w * 0.06f);
                float gapY = Math.max(stampH * 2.0f, h * 0.12f);
                float stepX = stampW + gapX;
                float stepY = stampH + gapY;
                int row = 0;
                // Extend one step past each edge so rotated marks still cover the corners.
                for (float y = lly - stepY; y <= lly + h + stepY; y += stepY, row++) {
                    // Stagger alternate rows for a denser, less gridded look.
                    float offset = (row % 2 == 0) ? 0f : stepX / 2f;
                    for (float x = llx - stepX + offset; x <= llx + w + stepX; x += stepX) {
                        anchors.add(new float[]{x, y});
                        if (anchors.size() >= MAX_STAMPS_PER_PAGE) return anchors;
                    }
                }
            }
            case DIAGONAL -> {
                float dx = (float) Math.cos(radians);
                float dy = (float) Math.sin(radians);
                float step = stampW + Math.max(stampW * 0.4f, w * 0.05f);
                float diag = (float) Math.sqrt((double) w * w + (double) h * h);
                int n = (int) (diag / step) + 2;
                for (int i = -n; i <= n; i++) {
                    anchors.add(new float[]{cx + dx * step * i, cy + dy * step * i});
                    if (anchors.size() >= MAX_STAMPS_PER_PAGE) break;
                }
            }
            default -> anchors.add(singleAnchor(position, box, stampW, stampH));
        }
        return anchors;
    }

    /** Anchor for a single stamp at the requested corner/centre, inset by a small margin. */
    static float[] singleAnchor(Position position, PDRectangle box, float stampW, float stampH) {
        float llx = box.getLowerLeftX(), lly = box.getLowerLeftY();
        float w = box.getWidth(), h = box.getHeight();
        float halfW = stampW / 2f, halfH = stampH / 2f;
        float margin = Math.min(w, h) * 0.06f;
        float left = llx + margin + halfW;
        float right = llx + w - margin - halfW;
        float top = lly + h - margin - halfH;
        float bottom = lly + margin + halfH;
        float cx = llx + w / 2f, cy = lly + h / 2f;
        return switch (position) {
            case TOP_LEFT -> new float[]{left, top};
            case TOP_RIGHT -> new float[]{right, top};
            case BOTTOM_LEFT -> new float[]{left, bottom};
            case BOTTOM_RIGHT -> new float[]{right, bottom};
            default -> new float[]{cx, cy};
        };
    }

    /** Draws the text centred at the current origin (caller has already translated/rotated). */
    private static void drawText(PDPageContentStream cs, PDFont font, String text,
                                 float fontSize, float textWidth, float[] rgb) throws IOException {
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
        cs.newLineAtOffset(-textWidth / 2f, -fontSize / 3f);
        cs.showText(text);
        cs.endText();
    }

    /** Draws the image centred at the current origin (caller has already translated/rotated). */
    private static void drawImage(PDPageContentStream cs, PDImageXObject img,
                                  float stampW, float stampH) throws IOException {
        cs.drawImage(img, -stampW / 2f, -stampH / 2f, stampW, stampH);
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

    /** Parses a {@code #RRGGBB} (or {@code #RGB}) hex colour to normalised RGB; grey on null/invalid. */
    static float[] parseColor(String hex) {
        float[] grey = {0.6f, 0.6f, 0.6f};
        if (hex == null || hex.isBlank()) return grey;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 3) {
                int r = Integer.parseInt(s.substring(0, 1), 16);
                int g = Integer.parseInt(s.substring(1, 2), 16);
                int b = Integer.parseInt(s.substring(2, 3), 16);
                return new float[]{(r * 17) / 255f, (g * 17) / 255f, (b * 17) / 255f};
            }
            if (s.length() == 6) {
                int r = Integer.parseInt(s.substring(0, 2), 16);
                int g = Integer.parseInt(s.substring(2, 4), 16);
                int b = Integer.parseInt(s.substring(4, 6), 16);
                return new float[]{r / 255f, g / 255f, b / 255f};
            }
        } catch (NumberFormatException ignored) {
            // fall through to grey
        }
        return grey;
    }

    private static BufferedImage readImage(java.nio.file.Path image) throws IOException {
        BufferedImage bi = ImageIO.read(image.toFile());
        if (bi == null) throw new IOException("Cannot read watermark image: " + image.getFileName());
        return bi;
    }

    private static BufferedImage readImage(byte[] image) throws IOException {
        BufferedImage bi = ImageIO.read(new java.io.ByteArrayInputStream(image));
        if (bi == null) throw new IOException("Cannot read watermark image: unsupported or corrupt image data.");
        return bi;
    }
}
