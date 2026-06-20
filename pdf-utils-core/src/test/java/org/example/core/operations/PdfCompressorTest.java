package org.example.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
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

    @Test
    void modestTargetReducesQualityBeforeDownscaling() throws Exception {
        // A smooth image stored losslessly: re-encoding it as JPEG at full
        // resolution already shrinks it well below a modest target, so the
        // compressor should NOT need to reduce the image's pixel dimensions.
        Path src = createSmoothImagePdf(1200);
        long original = src.toFile().length();
        Path out = tmp.resolve("modest.pdf");

        long target = original * 8 / 10; // 80% — reachable by quality reduction alone
        CompressResult result = PdfCompressor.execute(new CompressOptions(src, target, out));

        assertTrue(result.targetReached(), "a modest target should be reached");
        assertTrue(result.resultBytes() <= target);
        assertEquals(1200, firstImageWidth(out),
            "a modest target must be met by lowering quality, not by downscaling the image");
    }

    /** Width (in pixels) of the first image embedded on page 1 of {@code pdf}. */
    private int firstImageWidth(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDResources res = doc.getPage(0).getResources();
            for (COSName name : res.getXObjectNames()) {
                PDXObject xobj = res.getXObject(name);
                if (xobj instanceof PDImageXObject img) return img.getWidth();
            }
        }
        return -1;
    }

    /** An A4 page filled with a smooth, JPEG-friendly image stored losslessly. */
    private Path createSmoothImagePdf(int px) throws IOException {
        Path path = tmp.resolve("smooth.pdf");
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                int r = (int) ((Math.sin(x * 0.04) + 1) * 120);
                int g = (int) ((Math.sin(y * 0.05) + 1) * 120);
                int b = (int) ((Math.sin((x + y) * 0.03) + 1) * 120);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject pdfImg = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdfImg, 0, 0, 595, 842);
            }
            doc.save(path.toFile());
        }
        return path;
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
