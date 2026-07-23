package com.pdfconduit.core.operations;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageMarksOptions;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfPageMarkerTest {

    @TempDir Path tmp;

    @Test
    void stampsPageNumbersAndHeaderText() throws Exception {
        Path src = pdf(3);
        Path out = tmp.resolve("marked.pdf");

        PdfPageMarker.execute(new PageMarksOptions(src,
            "Quarterly Report", null, null,
            null, "{page} / {pages}", null,
            10f, 36f, false, 1, "", out));

        assertTrue(Files.exists(out));
        String text;
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(3, doc.getNumberOfPages());
            text = new PDFTextStripper().getText(doc);
        }
        assertTrue(text.contains("Quarterly Report"), "header text present, was: " + text);
        assertTrue(text.contains("1 / 3"), "page 1 number present, was: " + text);
        assertTrue(text.contains("3 / 3"), "page 3 number present, was: " + text);
    }

    @Test
    void batesPrefixZeroPadsAndSkipFirstLeavesPageOneUnmarked() throws Exception {
        byte[] src = Files.readAllBytes(pdf(2));

        byte[] result = PdfPageMarker.executeBytes(src,
            null, null, null,
            "{page}", null, null,
            9f, 24f, true, 41, "ACME-");

        try (PDDocument doc = Loader.loadPDF(result)) {
            String page1 = pageText(doc, 1);
            String page2 = pageText(doc, 2);
            assertFalse(page1.contains("ACME-"), "first page skipped, was: " + page1);
            // startNumber 41, page index 1 → 42, zero-padded to six digits.
            assertTrue(page2.contains("ACME-000042"), "Bates number on page 2, was: " + page2);
        }
    }

    /** PM-1: Polish/non-Latin diacritics must render as real Unicode, not '?'. */
    @Test
    void polishCharactersRenderAsUnicodeNotQuestionMarks() throws Exception {
        byte[] src = Files.readAllBytes(pdf(1));
        String polish = "Zażółć gęślą jaźń";   // covers ą ć ę ł ń ó ś ź ż

        byte[] result = PdfPageMarker.executeBytes(src,
            polish, null, null,
            null, "Strona {page} z {pages}", null,
            10f, 36f, false, 1, "");

        try (PDDocument doc = Loader.loadPDF(result)) {
            String text = new PDFTextStripper().getText(doc);
            assertFalse(text.contains("?"), "no '?' fallbacks, was: " + text);
            assertTrue(text.contains(polish), "Polish header present, was: " + text);
            for (String ch : new String[]{"ą", "ć", "ę", "ł", "ń", "ó", "ś", "ź", "ż"}) {
                assertTrue(text.contains(ch), "diacritic " + ch + " present, was: " + text);
            }
        }
    }

    /** PM-2: with a Bates prefix, {page} is prefixed/zero-padded but {pages} is the plain total. */
    @Test
    void batesPrefixAppliesToCurrentNumberButPagesTotalStaysPlain() throws Exception {
        byte[] src = Files.readAllBytes(pdf(3));

        byte[] result = PdfPageMarker.executeBytes(src,
            null, null, null,
            "{page}/{pages}", null, null,
            10f, 36f, false, 1, "HUH");

        try (PDDocument doc = Loader.loadPDF(result)) {
            String page1 = pageText(doc, 1);
            assertTrue(page1.contains("HUH000001/3"),
                "prefixed current + plain total, was: " + page1);
            assertFalse(page1.contains("HUH000003"),
                "total must not be Bates-formatted, was: " + page1);
            String page3 = pageText(doc, 3);
            assertTrue(page3.contains("HUH000003/3"), "page 3 current, was: " + page3);
        }
    }

    @Test
    void requiresAtLeastOneSlot() throws Exception {
        Path src = pdf(1);
        Path out = tmp.resolve("none.pdf");
        assertThrows(PdfOperationException.class, () -> PdfPageMarker.execute(new PageMarksOptions(src,
            null, null, null, null, null, null, 10f, 36f, false, 1, "", out)));
    }

    private String pageText(PDDocument doc, int page) throws IOException {
        PDFTextStripper s = new PDFTextStripper();
        s.setStartPage(page);
        s.setEndPage(page);
        return s.getText(doc);
    }

    private Path pdf(int pages) throws IOException {
        Path p = tmp.resolve("src-" + pages + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(p.toFile());
        }
        return p;
    }
}
