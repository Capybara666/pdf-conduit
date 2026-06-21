package com.pdfconduit.core.operations;

import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PdfToTextOptions;
import com.pdfconduit.core.model.PdfToTextResult;
import com.pdfconduit.core.model.TextFormat;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfTextExporterTest {

    @TempDir Path tmp;

    @Test
    void extractsAllPagesToTxt() throws Exception {
        Path src = createPdf("ALPHA", "BETA", "GAMMA");
        Path dir = tmp.resolve("out");

        PdfToTextResult result = PdfTextExporter.execute(
            new PdfToTextOptions(src, TextFormat.TXT, PageRange.ALL, dir, "doc"));

        assertEquals(dir.resolve("doc.txt"), result.output());
        String text = Files.readString(result.output());
        assertTrue(text.contains("ALPHA"), text);
        assertTrue(text.contains("BETA"), text);
        assertTrue(text.contains("GAMMA"), text);
    }

    @Test
    void extractsOnlySelectedPages() throws Exception {
        Path src = createPdf("ALPHA", "BETA", "GAMMA");
        Path dir = tmp.resolve("sub");

        PdfTextExporter.execute(
            new PdfToTextOptions(src, TextFormat.TXT, new PageRange(List.of(2)), dir, "doc"));

        String text = Files.readString(dir.resolve("doc.txt"));
        assertTrue(text.contains("BETA"), text);
        assertFalse(text.contains("ALPHA"), text);
        assertFalse(text.contains("GAMMA"), text);
    }

    @Test
    void docxWithoutLibreOfficeFailsClearly() throws Exception {
        // Only meaningful when LibreOffice is absent (CI / headless dev box).
        Assumptions.assumeFalse(DocumentConverter.officeConversionAvailable(),
            "LibreOffice present — skipping the 'missing' path");
        Path src = createPdf("ALPHA");

        PdfOperationException ex = assertThrows(PdfOperationException.class, () ->
            PdfTextExporter.execute(new PdfToTextOptions(
                src, TextFormat.DOCX, PageRange.ALL, tmp.resolve("d"), "doc")));
        assertTrue(ex.getMessage().contains("LibreOffice"), ex.getMessage());
    }

    private Path createPdf(String... pageTexts) throws IOException {
        Path path = tmp.resolve("src-" + pageTexts.length + "-" + System.nanoTime() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (String t : pageTexts) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.newLineAtOffset(100, 700);
                    cs.showText(t);
                    cs.endText();
                }
            }
            doc.save(path.toFile());
        }
        return path;
    }
}
