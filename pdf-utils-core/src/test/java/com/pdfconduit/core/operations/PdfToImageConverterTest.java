package com.pdfconduit.core.operations;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import com.pdfconduit.core.model.ImageFormat;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PdfToImageOptions;
import com.pdfconduit.core.model.PdfToImageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfToImageConverterTest {

    @TempDir Path tmp;

    @Test
    void exportsEveryPageAsPng() throws Exception {
        Path src = createPdf(3);
        Path out = tmp.resolve("out");

        PdfToImageResult result = PdfToImageConverter.execute(
            new PdfToImageOptions(src, ImageFormat.PNG, 72, PageRange.ALL, 1f, out, "doc"));

        assertEquals(3, result.count());
        assertEquals(List.of("doc_p1.png", "doc_p2.png", "doc_p3.png"),
            result.images().stream().map(p -> p.getFileName().toString()).toList());
        for (Path img : result.images()) {
            assertTrue(Files.size(img) > 0, "image should not be empty");
            BufferedImage read = ImageIO.read(img.toFile());
            assertNotNull(read, "written file should be a readable image");
        }
    }

    @Test
    void exportsOnlySelectedPagesAsJpeg() throws Exception {
        Path src = createPdf(10);
        Path out = tmp.resolve("jpg");

        PdfToImageResult result = PdfToImageConverter.execute(
            new PdfToImageOptions(src, ImageFormat.JPEG, 72, new PageRange(List.of(2, 5)), 0.8f, out, "scan"));

        assertEquals(2, result.count());
        // Page numbers are zero-padded to the document's page count (10 → two digits).
        assertEquals(List.of("scan_p02.jpg", "scan_p05.jpg"),
            result.images().stream().map(p -> p.getFileName().toString()).toList());
        assertNotNull(ImageIO.read(result.images().get(0).toFile()));
    }

    @Test
    void higherDpiYieldsLargerPixels() throws Exception {
        Path src = createPdf(1);

        var low = PdfToImageConverter.execute(new PdfToImageOptions(
            src, ImageFormat.PNG, 36, PageRange.ALL, 1f, tmp.resolve("lo"), "p"));
        var high = PdfToImageConverter.execute(new PdfToImageOptions(
            src, ImageFormat.PNG, 144, PageRange.ALL, 1f, tmp.resolve("hi"), "p"));

        int lowW = ImageIO.read(low.images().get(0).toFile()).getWidth();
        int highW = ImageIO.read(high.images().get(0).toFile()).getWidth();
        assertTrue(highW > lowW, "higher DPI should render more pixels");
    }

    @Test
    void pngTransparentBackgroundYieldsAlphaChannel() throws Exception {
        Path src = createPdf(1);
        byte[] pdf = Files.readAllBytes(src);

        List<byte[]> opaque = PdfToImageConverter.executeBytes(
            pdf, ImageFormat.PNG, 72, PageRange.ALL, 1f, false, false);
        List<byte[]> transparent = PdfToImageConverter.executeBytes(
            pdf, ImageFormat.PNG, 72, PageRange.ALL, 1f, true, false);

        BufferedImage opaqueImg = ImageIO.read(new java.io.ByteArrayInputStream(opaque.get(0)));
        BufferedImage transImg = ImageIO.read(new java.io.ByteArrayInputStream(transparent.get(0)));

        assertFalse(opaqueImg.getColorModel().hasAlpha(), "default render has no alpha");
        assertTrue(transImg.getColorModel().hasAlpha(), "transparent PNG must have an alpha channel");
        // A blank page renders with a fully transparent background.
        int corner = transImg.getRGB(0, 0);
        assertEquals(0, (corner >>> 24) & 0xFF, "background pixel should be fully transparent");
    }

    @Test
    void transparentBackgroundIgnoredForJpeg() throws Exception {
        // JPEG has no alpha; the flag must be silently ignored (no exception, no alpha).
        byte[] pdf = Files.readAllBytes(createPdf(1));
        List<byte[]> images = PdfToImageConverter.executeBytes(
            pdf, ImageFormat.JPEG, 72, PageRange.ALL, 0.8f, true, false);
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(images.get(0)));
        assertFalse(img.getColorModel().hasAlpha(), "JPEG output never has alpha");
    }

    @Test
    void grayscaleYieldsSingleComponentImage() throws Exception {
        byte[] pdf = Files.readAllBytes(createPdf(1));
        List<byte[]> images = PdfToImageConverter.executeBytes(
            pdf, ImageFormat.PNG, 72, PageRange.ALL, 1f, false, true);
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(images.get(0)));
        assertEquals(1, img.getColorModel().getNumComponents(),
            "grayscale render should have a single colour component");
    }

    @Test
    void transparentAndGrayscaleKeepsAlpha() throws Exception {
        // Precedence: transparency wins the render type, grayscale applied as a post-process.
        byte[] pdf = Files.readAllBytes(createPdf(1));
        List<byte[]> images = PdfToImageConverter.executeBytes(
            pdf, ImageFormat.PNG, 72, PageRange.ALL, 1f, true, true);
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(images.get(0)));
        assertTrue(img.getColorModel().hasAlpha(),
            "transparent+grayscale PNG keeps its alpha channel");
        assertEquals(0, (img.getRGB(0, 0) >>> 24) & 0xFF,
            "background stays transparent under grayscale");
    }

    private Path createPdf(int pages) throws IOException {
        Path path = tmp.resolve("src-" + pages + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(path.toFile());
        }
        return path;
    }
}
