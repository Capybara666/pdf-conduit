package com.pdfconduit.core.operations;

import com.pdfconduit.core.model.ImageFormat;
import com.pdfconduit.core.model.PageRange;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Defense-in-depth DPI clamp (S1): even if a caller asks for an absurd DPI, the core renderer caps
 * it at {@link PdfToImageConverter#MAX_RENDER_DPI} instead of trying to allocate an impossibly large
 * raster (which would OOM the JVM). Verified on a tiny page so the clamped render stays small.
 */
class PdfToImageDpiClampTest {

    /** A one-page PDF whose page is 10x10 pt, so even at the hard DPI cap the raster is tiny. */
    private static byte[] tinyPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(10, 10)));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    @Test
    void absurdDpi_isClampedToHardCap() throws Exception {
        List<byte[]> images = PdfToImageConverter.executeBytes(
            tinyPage(), ImageFormat.PNG, 1_000_000, PageRange.ALL, 1f);

        assertEquals(1, images.size());
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(images.get(0)));

        // ~ 10 pt / 72 * MAX_RENDER_DPI (PDFBox floors) — the render used the cap, not the 1,000,000
        // requested. Allow ±2 px for rounding; the key point is it's the cap, not the requested DPI.
        int expected = Math.round(10f / 72f * PdfToImageConverter.MAX_RENDER_DPI);
        assertTrue(Math.abs(img.getWidth() - expected) <= 2,
            "expected ~" + expected + " px but was " + img.getWidth());
        assertEquals(img.getWidth(), img.getHeight());
        // Sanity: nowhere near the ~138,000 px/side the un-clamped request would have demanded.
        assertTrue(img.getWidth() < 1_000, "clamped raster should be small, was " + img.getWidth());
    }
}
