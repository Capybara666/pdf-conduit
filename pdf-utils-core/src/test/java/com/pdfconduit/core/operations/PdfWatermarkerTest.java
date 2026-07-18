package com.pdfconduit.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.WatermarkOptions;
import com.pdfconduit.core.model.WatermarkOptions.Layout;
import com.pdfconduit.core.model.WatermarkOptions.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfWatermarkerTest {

    @TempDir Path tmp;

    @Test
    void textWatermarkPreservesPageCount() throws Exception {
        Path src = pdf(3);
        Path out = tmp.resolve("text-wm.pdf");

        PdfWatermarker.execute(new WatermarkOptions(src, "CONFIDENTIAL", null, 0.3, 45, 0.7, out));

        assertTrue(out.toFile().exists());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(3, doc.getNumberOfPages());
        }
    }

    @Test
    void imageWatermarkEmbedsAnImageOnEveryPage() throws Exception {
        Path src = pdf(2);
        Path logo = logo();
        Path out = tmp.resolve("img-wm.pdf");

        PdfWatermarker.execute(new WatermarkOptions(src, null, logo, 0.4, 0, 0.7, out));

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            for (PDPage page : doc.getPages()) {
                assertTrue(hasImage(page.getResources()), "each page should carry the watermark image");
            }
        }
    }

    @Test
    void requiresTextOrImage() throws Exception {
        Path src = pdf(1);
        PdfOperationException ex = assertThrows(PdfOperationException.class,
            () -> PdfWatermarker.execute(
                new WatermarkOptions(src, null, null, 0.3, 45, 0.7, tmp.resolve("x.pdf"))));
        assertTrue(ex.getMessage().toLowerCase().contains("text") || ex.getMessage().toLowerCase().contains("image"));
    }

    @Test
    void fontSizeScalesWithScale() {
        PDRectangle a4 = PDRectangle.A4;
        float small = PdfWatermarker.fontSizeFor(0.4f, a4, 3f);
        float big = PdfWatermarker.fontSizeFor(0.8f, a4, 3f);
        assertEquals(2.0, big / small, 0.01, "doubling scale should double the font size");
    }

    @Test
    void imageWidthScalesWithScale() {
        PDRectangle a4 = PDRectangle.A4;
        float[] small = PdfWatermarker.imageSizeFor(0.3f, a4, 100, 100);
        float[] big = PdfWatermarker.imageSizeFor(0.6f, a4, 100, 100);
        assertEquals(2.0, big[0] / small[0], 0.01, "doubling scale should double the image width");
    }

    @Test
    void tiledTextWatermarkProducesValidPdfAndKeepsPageCount() throws Exception {
        Path src = pdf(2);
        Path out = tmp.resolve("tile-wm.pdf");

        PdfWatermarker.execute(new WatermarkOptions(src, "DRAFT", null, 0.25, 30, 0.25,
            Layout.TILE, Position.CENTER, "#ff0000", out));

        assertTrue(out.toFile().exists());
        assertTrue(out.toFile().length() > 0, "output PDF should be non-empty");
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(2, doc.getNumberOfPages());
        }
    }

    @Test
    void diagonalWatermarkViaBytesIsValid() throws Exception {
        byte[] src;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            var bos = new java.io.ByteArrayOutputStream();
            doc.save(bos);
            src = bos.toByteArray();
        }
        byte[] out = PdfWatermarker.executeBytes(src, "COPY", null, 0.2, 45, 0.3,
            Layout.DIAGONAL, Position.CENTER, "#0033aa");
        assertTrue(out.length > 0);
        try (PDDocument doc = Loader.loadPDF(out)) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    void parseColorHandlesHexAndFallsBackToGrey() {
        float[] red = PdfWatermarker.parseColor("#ff0000");
        assertEquals(1.0f, red[0], 0.001);
        assertEquals(0.0f, red[1], 0.001);
        float[] grey = PdfWatermarker.parseColor(null);
        assertEquals(0.6f, grey[0], 0.001);
        float[] greyOnGarbage = PdfWatermarker.parseColor("not-a-color");
        assertEquals(0.6f, greyOnGarbage[1], 0.001);
    }

    private boolean hasImage(PDResources res) throws IOException {
        if (res == null) return false;
        for (COSName name : res.getXObjectNames()) {
            PDXObject xobj = res.getXObject(name);
            if (xobj instanceof PDImageXObject) return true;
        }
        return false;
    }

    private Path pdf(int pages) throws IOException {
        Path p = tmp.resolve("src-" + pages + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(p.toFile());
        }
        return p;
    }

    private Path logo() throws IOException {
        Path p = tmp.resolve("logo.png");
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillOval(4, 4, 56, 56);
        g.dispose();
        ImageIO.write(img, "png", p.toFile());
        return p;
    }
}
