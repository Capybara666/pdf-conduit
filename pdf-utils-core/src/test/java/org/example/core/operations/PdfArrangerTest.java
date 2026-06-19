package org.example.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.ArrangeOptions;
import org.example.core.model.ArrangeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfArrangerTest {

    @TempDir Path tmp;

    @Test
    void reordersPages() throws Exception {
        Path src = createPdf(3);
        Path out = tmp.resolve("arranged.pdf");

        ArrangeResult result = PdfArranger.execute(new ArrangeOptions(src, List.of(3, 1, 2), out));

        assertEquals(3, result.pageCount());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(3, doc.getNumberOfPages());
        }
    }

    @Test
    void emptyOrderCopiesUnchanged() throws Exception {
        Path src = createPdf(4);
        Path out = tmp.resolve("copy.pdf");

        ArrangeResult result = PdfArranger.execute(new ArrangeOptions(src, List.of(), out));

        assertEquals(4, result.pageCount());
    }

    @Test
    void duplicatesAndOmissions() throws Exception {
        Path src = createPdf(5);
        Path out = tmp.resolve("dup.pdf");

        // keep page 1 twice, drop everything else except page 3
        ArrangeResult result = PdfArranger.execute(new ArrangeOptions(src, List.of(1, 1, 3), out));

        assertEquals(3, result.pageCount());
    }

    @Test
    void outOfRangePageThrows() throws Exception {
        Path src = createPdf(2);
        Path out = tmp.resolve("bad.pdf");

        assertThrows(PdfOperationException.class,
            () -> PdfArranger.execute(new ArrangeOptions(src, List.of(1, 5), out)));
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
