package org.example.core.operations;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.example.core.model.CompressOptions;
import org.example.core.model.CompressResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfCompressorTest {

    @TempDir Path tmp;

    @Test
    void compressesImageHeavyPdf() throws Exception {
        Path src = createImageHeavyPdf();
        long originalSize = src.toFile().length();
        Path out = tmp.resolve("compressed.pdf");

        CompressResult result = PdfCompressor.execute(
            new CompressOptions(src, 1024, out)); // 1 KB — unreachable, triggers compression

        assertTrue(out.toFile().exists());
        assertTrue(result.resultBytes() <= originalSize);
        assertFalse(result.targetReached());
        assertEquals(out, result.output());
    }

    @Test
    void reachesTargetForSmallFile() throws Exception {
        Path src = createTextPdf(2);
        Path out = tmp.resolve("small.pdf");

        CompressResult result = PdfCompressor.execute(
            new CompressOptions(src, 100 * 1024, out)); // 100 KB — easily reachable

        assertTrue(result.targetReached());
        assertTrue(result.resultBytes() <= 100 * 1024);
    }

    private Path createImageHeavyPdf() throws IOException {
        Path path = tmp.resolve("heavy.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < 2; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                BufferedImage img = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                g.setColor(new Color(p * 80, 100, 200));
                g.fillRect(0, 0, 1000, 1000);
                g.dispose();
                PDImageXObject pdfImg = LosslessFactory.createFromImage(doc, img);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(pdfImg, 0, 0, 595, 842);
                }
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private Path createTextPdf(int pages) throws IOException {
        Path path = tmp.resolve("text-" + pages + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(path.toFile());
        }
        return path;
    }
}
