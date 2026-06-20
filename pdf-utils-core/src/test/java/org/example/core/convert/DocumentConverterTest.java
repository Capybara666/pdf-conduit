package org.example.core.convert;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentConverterTest {

    @TempDir Path tmp;

    @Test
    void classifiesByExtension() {
        assertEquals(DocumentConverter.Kind.PDF, DocumentConverter.classify(Path.of("a.pdf")));
        assertEquals(DocumentConverter.Kind.IMAGE, DocumentConverter.classify(Path.of("a.PNG")));
        assertEquals(DocumentConverter.Kind.IMAGE, DocumentConverter.classify(Path.of("a.jpeg")));
        assertEquals(DocumentConverter.Kind.OFFICE, DocumentConverter.classify(Path.of("a.docx")));
        assertEquals(DocumentConverter.Kind.OFFICE, DocumentConverter.classify(Path.of("a.txt")));
        assertEquals(DocumentConverter.Kind.UNSUPPORTED, DocumentConverter.classify(Path.of("a.zip")));
        assertEquals(DocumentConverter.Kind.UNSUPPORTED, DocumentConverter.classify(Path.of("noext")));
    }

    @Test
    void pdfPassesThroughUnchanged() throws Exception {
        Path pdf = tmp.resolve("doc.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(pdf.toFile());
        }
        List<Path> temps = new ArrayList<>();
        Path result = DocumentConverter.ensurePdf(pdf, null, temps);

        assertEquals(pdf, result);          // same file, no copy
        assertTrue(temps.isEmpty());        // nothing to clean up
    }

    @Test
    void webpIsConvertedToPdf() throws Exception {
        // WebP is advertised as a supported image type, so it must actually decode.
        Path webp = tmp.resolve("sample.webp");
        try (var in = getClass().getResourceAsStream("/sample.webp")) {
            assertNotNull(in, "missing test fixture sample.webp");
            Files.copy(in, webp);
        }
        assertEquals(DocumentConverter.Kind.IMAGE, DocumentConverter.classify(webp));

        List<Path> temps = new ArrayList<>();
        Path pdf = DocumentConverter.ensurePdf(webp, null, temps);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    void imageIsConvertedToPdf() throws Exception {
        Path png = tmp.resolve("pic.png");
        BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 120, 80);
        g.dispose();
        ImageIO.write(img, "png", png.toFile());

        List<Path> temps = new ArrayList<>();
        Path pdf = DocumentConverter.ensurePdf(png, null, temps);

        assertEquals(1, temps.size());
        assertEquals(pdf, temps.get(0));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }
}
