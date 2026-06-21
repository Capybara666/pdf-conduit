package com.pdfconduit.app.cli;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.PageSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CliSourcesTest {

    @TempDir Path tmp;

    @Test
    void routesPdfToPageSourceAndImageToImageSource() throws Exception {
        Path pdf = createPdf();
        Path png = createPng();
        List<Path> temps = new ArrayList<>();

        List<PageSource> sources = CliSources.build(List.of(pdf, png), PageSize.A4, temps);

        assertEquals(2, sources.size());
        assertInstanceOf(PageSource.PdfPageSource.class, sources.get(0));
        assertInstanceOf(PageSource.ImageSource.class, sources.get(1));
        assertEquals(PageSize.A4, ((PageSource.ImageSource) sources.get(1)).targetSize());
        assertTrue(temps.isEmpty(), "PDF/image inputs need no conversion temps");
    }

    @Test
    void rejectsUnsupportedFile() {
        Path zip = tmp.resolve("archive.zip");
        List<Path> temps = new ArrayList<>();

        PdfOperationException ex = assertThrows(PdfOperationException.class,
            () -> CliSources.build(List.of(zip), PageSize.FIT, temps));
        assertTrue(ex.getMessage().toLowerCase().contains("unsupported"),
            "error should name the unsupported type, got: " + ex.getMessage());
    }

    private Path createPdf() throws Exception {
        Path pdf = tmp.resolve("doc.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private Path createPng() throws Exception {
        Path png = tmp.resolve("pic.png");
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        return png;
    }
}
