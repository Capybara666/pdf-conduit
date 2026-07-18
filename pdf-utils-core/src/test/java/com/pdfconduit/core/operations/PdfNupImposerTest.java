package com.pdfconduit.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import com.pdfconduit.core.model.NupLayout;
import com.pdfconduit.core.model.NupOptions;
import com.pdfconduit.core.model.PdfResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfNupImposerTest {

    @TempDir Path tmp;

    @Test
    void twoUpHalvesTheSheetCount() throws Exception {
        Path src = createPdf(4);
        Path out = tmp.resolve("2up.pdf");

        PdfResult r = PdfNupImposer.execute(new NupOptions(src, NupLayout.TWO_UP, false, out));

        assertEquals(2, r.pageCount());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(2, doc.getNumberOfPages());
        }
    }

    @Test
    void fourUpPacksFourPagesPerSheetAndRoundsUp() throws Exception {
        byte[] out = PdfNupImposer.executeBytes(bytes(createPdf(5)), NupLayout.FOUR_UP, false);
        try (PDDocument doc = Loader.loadPDF(out)) {
            assertEquals(2, doc.getNumberOfPages());   // ceil(5 / 4)
        }
    }

    @Test
    void nineUpUsesAThreeByThreeGrid() throws Exception {
        byte[] out = PdfNupImposer.executeBytes(bytes(createPdf(9)), NupLayout.NINE_UP, false);
        try (PDDocument doc = Loader.loadPDF(out)) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    void bookletPadsToMultipleOfFourAndImposesTwoPerFace() throws Exception {
        // 6 pages → padded to 8 → 4 faces (2 pages each).
        byte[] out = PdfNupImposer.executeBytes(bytes(createPdf(6)), NupLayout.TWO_UP, true);
        try (PDDocument doc = Loader.loadPDF(out)) {
            assertEquals(4, doc.getNumberOfPages());
        }
    }

    private byte[] bytes(Path p) throws IOException {
        return Files.readAllBytes(p);
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
