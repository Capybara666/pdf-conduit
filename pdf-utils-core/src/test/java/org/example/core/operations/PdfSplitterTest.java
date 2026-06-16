package org.example.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.example.core.model.PageRange;
import org.example.core.model.SplitOptions;
import org.example.core.model.SplitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfSplitterTest {

    @TempDir Path tmp;

    @Test
    void extractsSinglePage() throws Exception {
        Path src = createPdf(5);
        Path out = tmp.resolve("split.pdf");

        SplitResult result = PdfSplitter.execute(
            new SplitOptions(src, new PageRange(List.of(2)), out));

        assertEquals(1, result.pageCount());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    void extractsRange() throws Exception {
        Path src = createPdf(10);
        Path out = tmp.resolve("range.pdf");

        SplitResult result = PdfSplitter.execute(
            new SplitOptions(src, new PageRange(List.of(2, 3, 4)), out));

        assertEquals(3, result.pageCount());
    }

    @Test
    void extractsAllWhenRangeIsAll() throws Exception {
        Path src = createPdf(4);
        Path out = tmp.resolve("all.pdf");

        SplitResult result = PdfSplitter.execute(
            new SplitOptions(src, PageRange.ALL, out));

        assertEquals(4, result.pageCount());
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
