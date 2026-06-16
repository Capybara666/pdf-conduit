package org.example.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.ImageToPdfOptions;
import org.example.core.model.PageSize;
import org.example.core.model.PdfResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImageToPdfConverterTest {

    @TempDir Path tmp;

    @Test
    void convertsSingleImageFitMode() throws Exception {
        Path img = createTestImage(800, 600);
        Path out = tmp.resolve("out.pdf");

        PdfResult result = ImageToPdfConverter.execute(
            new ImageToPdfOptions(List.of(img), PageSize.FIT, out));

        assertEquals(1, result.pageCount());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(1, doc.getNumberOfPages());
            float w = doc.getPage(0).getMediaBox().getWidth();
            float h = doc.getPage(0).getMediaBox().getHeight();
            assertEquals(800f, w, 1f);
            assertEquals(600f, h, 1f);
        }
    }

    @Test
    void convertsMultipleImagesToA4() throws Exception {
        Path img1 = createTestImage(400, 300);
        Path img2 = createTestImage(200, 200);
        Path out = tmp.resolve("multi.pdf");

        PdfResult result = ImageToPdfConverter.execute(
            new ImageToPdfOptions(List.of(img1, img2), PageSize.A4, out));

        assertEquals(2, result.pageCount());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(2, doc.getNumberOfPages());
            float w = doc.getPage(0).getMediaBox().getWidth();
            assertEquals(595.28f, w, 1f);
        }
    }

    private Path createTestImage(int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        Path path = tmp.resolve("img-" + width + "x" + height + "-" + System.nanoTime() + ".png");
        ImageIO.write(img, "PNG", path.toFile());
        return path;
    }
}
