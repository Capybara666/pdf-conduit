package com.pdfconduit.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.RotateOptions;
import com.pdfconduit.core.model.RotateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfRotatorTest {

    @TempDir Path tmp;

    @Test
    void rotatesSinglePage90() throws Exception {
        Path src = createPdf(3);
        Path out = tmp.resolve("rotated.pdf");

        RotateResult result = PdfRotator.execute(
            new RotateOptions(src, new PageRange(List.of(2)), 90, out));

        assertEquals(1, result.rotatedPageCount());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(90, doc.getPage(1).getRotation());
            assertEquals(0,  doc.getPage(0).getRotation());
        }
    }

    @Test
    void rotatesAllPages() throws Exception {
        Path src = createPdf(4);
        Path out = tmp.resolve("all-rotated.pdf");

        RotateResult result = PdfRotator.execute(
            new RotateOptions(src, PageRange.ALL, 180, out));

        assertEquals(4, result.rotatedPageCount());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            for (int i = 0; i < 4; i++) {
                assertEquals(180, doc.getPage(i).getRotation());
            }
        }
    }

    private Path createPdf(int pages) throws IOException {
        Path path = tmp.resolve("r-" + pages + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(path.toFile());
        }
        return path;
    }
}
