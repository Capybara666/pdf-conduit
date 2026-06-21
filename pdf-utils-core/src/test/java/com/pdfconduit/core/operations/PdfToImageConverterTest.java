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

    private Path createPdf(int pages) throws IOException {
        Path path = tmp.resolve("src-" + pages + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(path.toFile());
        }
        return path;
    }
}
