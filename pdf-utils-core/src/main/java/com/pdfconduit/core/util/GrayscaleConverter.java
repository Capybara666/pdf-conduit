package com.pdfconduit.core.util;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * Converts an arbitrary {@link BufferedImage} to grayscale <em>reliably, regardless of the source
 * colorspace or {@link BufferedImage#getType() image type}</em> — RGB, indexed/palette
 * (GIF, paletted PNG), CMYK/YCCK JPEG, custom {@code TYPE_CUSTOM} rasters, and images that carry an
 * alpha channel.
 *
 * <p><b>Why not just {@code ColorConvertOp}?</b> A single {@code ColorConvertOp} onto a
 * {@code CS_GRAY} target (or a naive {@code image.getType()}-gated fast path) silently misbehaves on
 * anything that is not a plain packed-RGB raster: indexed and {@code TYPE_CUSTOM} sources (e.g. a
 * TwelveMonkeys-decoded CMYK JPEG) can throw or leave pixels in colour. That was the root cause of
 * "some images stay in colour".</p>
 *
 * <p>The approach here is bulletproof by construction:</p>
 * <ol>
 *   <li><b>Normalise</b> the source onto a canonical sRGB canvas by drawing it through
 *       {@link Graphics2D}. This makes {@link java.awt.image.ColorModel} do the colorspace work for
 *       <em>every</em> input type (indexed, CMYK, custom, gray-with-alpha), yielding predictable
 *       sRGB samples.</li>
 *   <li><b>Reduce</b> each pixel with the ITU-R BT.601 luma weights
 *       (0.299R + 0.587G + 0.114B) written straight into the raster, so no colour model gamma
 *       remap can re-introduce a colour cast.</li>
 * </ol>
 *
 * <p>Alpha is preserved: a source with an alpha channel yields a grayscale {@code TYPE_INT_ARGB}
 * image (R==G==B per pixel, original alpha kept); an opaque source yields a single-channel
 * {@code TYPE_BYTE_GRAY} image. An image that is already opaque {@code TYPE_BYTE_GRAY} is returned
 * unchanged.</p>
 */
public final class GrayscaleConverter {

    private GrayscaleConverter() {}

    /**
     * Returns a grayscale rendering of {@code src}. Never returns {@code null} for a non-null input;
     * the returned image always satisfies {@code R == G == B} for every pixel.
     */
    public static BufferedImage toGrayscale(BufferedImage src) {
        if (src == null) {
            return null;
        }
        // Already single-channel opaque gray — nothing to do.
        if (src.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            return src;
        }

        int w = src.getWidth();
        int h = src.getHeight();
        boolean hasAlpha = src.getColorModel().hasAlpha();

        // 1) Normalise ANY source colorspace/type onto a canonical sRGB canvas. Graphics2D performs
        //    the colorspace conversion (indexed → RGB, CMYK → RGB, custom → RGB, ...) for us.
        BufferedImage normalized = new BufferedImage(
            w, h, hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = normalized.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }

        // 2) Manual luma reduction straight into the destination raster.
        if (hasAlpha) {
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = normalized.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    int lum = luma(argb);
                    out.setRGB(x, y, (a << 24) | (lum << 16) | (lum << 8) | lum);
                }
            }
            return out;
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = out.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Write the raw luma sample so the gray color model performs no gamma remap.
                raster.setSample(x, y, 0, luma(normalized.getRGB(x, y)));
            }
        }
        return out;
    }

    /** BT.601 luma from a packed (A)RGB pixel, rounded and clamped to 0..255. */
    private static int luma(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int lum = (int) (0.299d * r + 0.587d * g + 0.114d * b + 0.5d);
        return lum < 0 ? 0 : (lum > 255 ? 255 : lum);
    }
}
