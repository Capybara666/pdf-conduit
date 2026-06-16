package org.example.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfMergerTest {

    @TempDir Path tmp;

    @Test
    void mergesTwoPdfs() throws Exception {
        Path a = createTestPdf(3);
        Path b = createTestPdf(2);
        Path out = tmp.resolve("merged.pdf");

        MergeResult result = PdfMerger.execute(new MergeOptions(
            List.of(new PageSource.PdfPageSource(a, PageRange.ALL),
                    new PageSource.PdfPageSource(b, PageRange.ALL)),
            out
        ));

        assertEquals(5, result.pageCount());
        assertTrue(out.toFile().exists());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(5, doc.getNumberOfPages());
        }
    }

    @Test
    void mergesWithPageRange() throws Exception {
        Path a = createTestPdf(5);
        Path out = tmp.resolve("range.pdf");

        MergeResult result = PdfMerger.execute(new MergeOptions(
            List.of(new PageSource.PdfPageSource(a, new PageRange(List.of(1, 3, 5)))),
            out
        ));

        assertEquals(3, result.pageCount());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(3, doc.getNumberOfPages());
        }
    }

    private Path createTestPdf(int pages) throws IOException {
        Path path = tmp.resolve("test-" + pages + "-" + System.nanoTime() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(100, 700);
                    cs.showText("Page " + (i + 1));
                    cs.endText();
                }
            }
            doc.save(path.toFile());
        }
        return path;
    }
}
