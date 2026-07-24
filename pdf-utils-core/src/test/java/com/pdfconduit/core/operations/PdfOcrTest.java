package com.pdfconduit.core.operations;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PageSize;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PdfOcr}. Crucially these do NOT require the {@code tesseract} binary to be
 * installed: the availability-dependent behaviour is guarded so the suite never hangs or fails on a
 * build box without Tesseract. The always-on tests cover the invisible-layer font path (the bundled
 * Unicode TTF loads + encodes) and the clean disabled-path error when the binary is absent. The live
 * end-to-end OCR test only runs when {@code tesseract} is actually present.
 */
class PdfOcrTest {

    /** The bundled DejaVu TTF loads as an embeddable Type0 font and encodes arbitrary Unicode. */
    @Test
    void bundledFontLoadsAndEncodesUnicode() throws Exception {
        try (PDDocument doc = new PDDocument();
             InputStream in = PdfOcr.class.getResourceAsStream("/fonts/DejaVuSans.ttf")) {
            assertNotNull(in, "bundled OCR font resource /fonts/DejaVuSans.ttf must be present");
            PDType0Font font = PDType0Font.load(doc, in, true);
            // Latin, accented and Cyrillic all encode without throwing (the invisible layer needs this).
            assertTrue(font.getStringWidth("Hello") > 0);
            assertTrue(font.getStringWidth("café éè") > 0);
            assertTrue(font.getStringWidth("Привет") > 0); // "Привет"
        }
    }

    /** {@link PdfOcr#available()} must return quickly (never hang) whether or not tesseract exists. */
    @Test
    void availabilityCheckDoesNotHang() {
        long start = System.currentTimeMillis();
        boolean available = PdfOcr.available();
        long elapsed = System.currentTimeMillis() - start;
        // Discovery probes a few paths + one `--version` with a bounded timeout; it must not block.
        assertTrue(elapsed < 30_000, "availability probe should be bounded, took " + elapsed + "ms");
        assertEquals(available, PdfOcr.available(), "availability should be stable/cached");
    }

    /** {@code --list-langs} output parses into clean, sorted codes — no binary required. */
    @Test
    void parsesLangListOutput() {
        String output = """
            List of available languages in "/usr/share/tesseract-ocr/5/tessdata/" (5):
            deu
            eng
            osd
            pol
            chi_sim
            """;
        assertEquals(List.of("chi_sim", "deu", "eng", "pol"), PdfOcr.parseLangList(output));
        // Garbage / empty output never throws — it just yields no languages.
        assertEquals(List.of(), PdfOcr.parseLangList(""));
        assertEquals(List.of(), PdfOcr.parseLangList("tesseract: error while loading shared libraries"));
    }

    /** Language discovery is bounded, cached and safe whether or not tesseract exists. */
    @Test
    void installedLanguagesNeverHangsOrThrows() {
        long start = System.currentTimeMillis();
        List<String> langs = PdfOcr.installedLanguages();
        long elapsed = System.currentTimeMillis() - start;
        assertNotNull(langs);
        assertTrue(elapsed < 30_000, "language discovery should be bounded, took " + elapsed + "ms");
        // Cached: the second call returns the same list without a new process spawn.
        assertSame(langs, PdfOcr.installedLanguages());
        if (!PdfOcr.available()) {
            assertTrue(langs.isEmpty(), "no tesseract -> no languages");
        }
    }

    /** When tesseract is NOT installed, OCR fails with a clear message rather than crashing. */
    @Test
    void reportsClearErrorWhenTesseractMissing() throws Exception {
        Assumptions.assumeFalse(PdfOcr.available(), "tesseract is installed — disabled-path test N/A");
        byte[] pdf = imageOnlyPdf("HELLO");
        PdfOperationException ex = assertThrows(PdfOperationException.class,
            () -> PdfOcr.executeBytes(pdf, "eng", 200));
        assertTrue(ex.getMessage().toLowerCase().contains("tesseract"),
            "message should mention tesseract: " + ex.getMessage());
    }

    /** Live end-to-end: OCR an image-only PDF and confirm the word is extractable from the output. */
    @Test
    void ocrMakesScannedTextSearchable() throws Exception {
        Assumptions.assumeTrue(PdfOcr.available(), "tesseract not installed — skipping live OCR test");
        byte[] pdf = imageOnlyPdf("HELLO");
        // Sanity: the input truly has no text layer yet.
        assertFalse(PdfTextExporter.extractTextBytes(pdf, PageRange.ALL).toUpperCase().contains("HELLO"));

        byte[] searchable = PdfOcr.executeBytes(pdf, "eng", 300);
        String text = PdfTextExporter.extractTextBytes(searchable, PageRange.ALL).toUpperCase();
        assertTrue(text.contains("HELLO"), "OCR'd text layer should contain the scanned word, got: " + text);
    }

    /**
     * The page-filter variant with an EMPTY set is a clean no-op: succeeds even without the
     * tesseract binary (redaction's re-OCR computes the set up front), page count intact, and no
     * text layer appears.
     */
    @Test
    void emptyPageSetIsANoOpWithoutTesseract() throws Exception {
        byte[] pdf = imageOnlyPdf("HELLO");
        byte[] out = PdfOcr.executeBytes(pdf, "eng", 300, Set.of());
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(out)) {
            assertEquals(1, doc.getNumberOfPages());
        }
        assertFalse(PdfTextExporter.extractTextBytes(out, PageRange.ALL).toUpperCase().contains("HELLO"),
            "no OCR text layer may be added when the page set is empty");
    }

    /**
     * Live: the page filter OCRs ONLY the listed pages — page 0 becomes searchable, page 1 (outside
     * the set) stays image-only. This is the contract redaction's re-OCR relies on: untouched pages
     * must not gain a duplicate text layer.
     */
    @Test
    void pageFilterLimitsOcrToGivenPages() throws Exception {
        Assumptions.assumeTrue(PdfOcr.available(), "tesseract not installed — skipping live OCR test");
        byte[] twoPages = twoPageScan("ALPHA", "BRAVO");

        byte[] out = PdfOcr.executeBytes(twoPages, "eng", 300, Set.of(0));

        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(out)) {
            assertEquals(2, doc.getNumberOfPages());
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String page1 = stripper.getText(doc).toUpperCase();
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            String page2 = stripper.getText(doc).toUpperCase();
            assertTrue(page1.contains("ALPHA"), "filtered-in page should be searchable, got: " + page1);
            assertFalse(page2.contains("BRAVO"), "filtered-out page must stay image-only, got: " + page2);
        }
    }

    /** Two single-page "scans" merged into one two-page, text-layer-free document. */
    private static byte[] twoPageScan(String firstWord, String secondWord) throws Exception {
        try (PDDocument first = org.apache.pdfbox.Loader.loadPDF(imageOnlyPdf(firstWord));
             PDDocument second = org.apache.pdfbox.Loader.loadPDF(imageOnlyPdf(secondWord));
             PDDocument merged = new PDDocument()) {
            merged.importPage(first.getPage(0));
            merged.importPage(second.getPage(0));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            merged.save(baos);
            return baos.toByteArray();
        }
    }

    /** Builds an image-only (no text layer) PDF with {@code word} rendered as pixels — a "scan". */
    private static byte[] imageOnlyPdf(String word) throws Exception {
        BufferedImage img = new BufferedImage(1000, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 160));
        g.drawString(word, 60, 200);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        return ImageToPdfConverter.executeBytes(List.of(png.toByteArray()), PageSize.FIT);
    }

    /** Language spec is validated (hardening) — valid codes pass, junk is rejected, blank defaults. */
    @Test
    void languagesAreValidated() throws Exception {
        // Valid forms accepted (normalised/trimmed).
        assertEquals("eng", PdfOcr.validateLanguages("eng"));
        assertEquals("eng+pol", PdfOcr.validateLanguages(" eng+pol "));
        assertEquals("chi_sim", PdfOcr.validateLanguages("chi_sim"));
        // Blank/null default to the built-in language.
        assertEquals(PdfOcr.DEFAULT_LANGUAGES, PdfOcr.validateLanguages(null));
        assertEquals(PdfOcr.DEFAULT_LANGUAGES, PdfOcr.validateLanguages("   "));
        // Junk / metacharacters / over-length are rejected with a clear error.
        for (String bad : new String[]{"eng;rm -rf", "eng foo", "../../etc", "eng`id`",
                "e".repeat(65), "eng$(whoami)"}) {
            assertThrows(PdfOperationException.class, () -> PdfOcr.validateLanguages(bad),
                "should reject: " + bad);
        }
    }
}
