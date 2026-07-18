package com.pdfconduit.core.operations;

import com.pdfconduit.core.model.CropOptions;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfCropperTest {

    @TempDir Path tmp;

    @Test
    void trimsCropBoxByPointMargins() throws Exception {
        Path src = pdf(2, 200, 200);
        Path out = tmp.resolve("cropped.pdf");

        PdfCropper.execute(new CropOptions(src, 10, 20, 30, 40, false, out));

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(2, doc.getNumberOfPages());
            PDRectangle box = doc.getPage(0).getCropBox();
            // left/bottom raise the lower-left corner; right/top lower the upper-right corner.
            assertEquals(40f, box.getLowerLeftX(), 0.01f);
            assertEquals(30f, box.getLowerLeftY(), 0.01f);
            assertEquals(180f, box.getUpperRightX(), 0.01f);   // 200 - 20
            assertEquals(190f, box.getUpperRightY(), 0.01f);   // 200 - 10
            // Media box is untouched — the crop is reversible.
            assertEquals(200f, doc.getPage(0).getMediaBox().getWidth(), 0.01f);
        }
    }

    @Test
    void millimetreMarginsConvertToPoints() throws Exception {
        // A 10 mm left margin == 10 * 72/25.4 ≈ 28.35 pt.
        byte[] out = PdfCropper.executeBytes(bytes(1, 300, 300), 0, 0, 0, 10, true);
        try (PDDocument doc = Loader.loadPDF(out)) {
            assertEquals(28.346f, doc.getPage(0).getCropBox().getLowerLeftX(), 0.05f);
        }
    }

    @Test
    void oversizedMarginsClampToASliverInsteadOfInverting() throws Exception {
        byte[] out = PdfCropper.executeBytes(bytes(1, 100, 100), 500, 500, 500, 500, false);
        try (PDDocument doc = Loader.loadPDF(out)) {
            PDRectangle box = doc.getPage(0).getCropBox();
            assertTrue(box.getWidth() > 0, "width must stay positive");
            assertTrue(box.getHeight() > 0, "height must stay positive");
        }
    }

    private Path pdf(int pages, float w, float h) throws Exception {
        Path p = tmp.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(new PDRectangle(w, h)));
            doc.save(p.toFile());
        }
        return p;
    }

    private byte[] bytes(int pages, float w, float h) throws Exception {
        try (PDDocument doc = new PDDocument();
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(new PDRectangle(w, h)));
            doc.save(bos);
            return bos.toByteArray();
        }
    }
}
