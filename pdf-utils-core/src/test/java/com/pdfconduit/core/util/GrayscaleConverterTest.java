package com.pdfconduit.core.util;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link GrayscaleConverter} converts EVERY image to grayscale regardless of the
 * source colorspace / {@link BufferedImage} type — the RGB path was working before, but
 * indexed/palette, alpha and (approximated here via a custom raster) non-RGB sources previously
 * stayed in colour.
 */
class GrayscaleConverterTest {

    /** Every pixel of a grayscale image must have equal R, G and B channels. */
    private static void assertGray(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                assertEquals(r, g, "R!=G at " + x + "," + y);
                assertEquals(g, b, "G!=B at " + x + "," + y);
            }
        }
    }

    @Test
    void rgbColorImageBecomesGray() {
        BufferedImage src = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = src.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 8, 16);
        g.setColor(new Color(0, 200, 40)); // vivid green — R!=G!=B in the source
        g.fillRect(8, 0, 8, 16);
        g.dispose();

        // sanity: the source really is coloured
        int c = src.getRGB(0, 0);
        assertTrue(((c >> 16) & 0xFF) != (c & 0xFF), "source should be coloured");

        assertGray(GrayscaleConverter.toGrayscale(src));
    }

    @Test
    void indexedPaletteImageBecomesGray() {
        // A 4-entry colour palette (the classic "ColorConvertOp fails silently" case).
        byte[] r = {(byte) 255, 0, 0, (byte) 255};
        byte[] gg = {0, (byte) 255, 0, (byte) 255};
        byte[] b = {0, 0, (byte) 255, 0};
        IndexColorModel icm = new IndexColorModel(2, 4, r, gg, b);
        BufferedImage src = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_INDEXED, icm);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                src.getRaster().setSample(x, y, 0, (x + y) % 4);
            }
        }
        assertEquals(BufferedImage.TYPE_BYTE_INDEXED, src.getType());

        assertGray(GrayscaleConverter.toGrayscale(src));
    }

    @Test
    void argbImageKeepsAlphaButBecomesGray() {
        BufferedImage src = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int a = x * 30;                       // varying alpha
                src.setRGB(x, y, (a << 24) | 0x00C81428); // opaque-looking magenta-ish colour
            }
        }
        BufferedImage out = GrayscaleConverter.toGrayscale(src);
        assertGray(out);
        // alpha preserved
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                assertEquals((src.getRGB(x, y) >>> 24), (out.getRGB(x, y) >>> 24),
                    "alpha not preserved at " + x + "," + y);
            }
        }
    }

    @Test
    void alreadyGrayImageIsReturnedUnchanged() {
        BufferedImage src = new BufferedImage(4, 4, BufferedImage.TYPE_BYTE_GRAY);
        assertSame(src, GrayscaleConverter.toGrayscale(src));
    }

    @Test
    void nullIsTolerated() {
        assertNull(GrayscaleConverter.toGrayscale(null));
    }
}
