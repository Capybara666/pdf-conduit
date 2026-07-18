package com.pdfconduit.core.analyze;

import com.pdfconduit.core.model.RedactRegion;
import com.pdfconduit.core.operations.PdfRedactor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ground-truth proof that a {@link PiiScanner} finding's regions, fed straight into
 * {@link PdfRedactor}, actually <b>cover and remove</b> the personal data — not merely
 * underline it (the mis-placed-Y bug this test was written to lock down).
 *
 * <p>The document is drawn with the PII value on its own line at a known baseline, so the
 * text's true vertical band is known independently of the code under test. We then assert,
 * three independent ways:
 * <ol>
 *   <li><b>Text</b>: the value can no longer be extracted from the redacted output;</li>
 *   <li><b>Geometry</b>: the finding's box straddles the baseline and covers the glyph body
 *       (its top sits <em>above</em> the baseline), which the old {@code [baseline, baseline+h]}
 *       box did not;</li>
 *   <li><b>Pixels</b>: rendering the redacted page shows solid black at the exact heights the
 *       glyph ink occupied (above the baseline) — the old underline box left these white.</li>
 * </ol>
 */
class RedactionCoverageTest {

    private static final String EMAIL = "john.doe@example.com";

    // Layout knobs shared by the PDF builder and the assertions.
    private static final float PAGE_H = PDRectangle.A4.getHeight();   // 841.89
    private static final float BASELINE_PDF_Y = 760f;                 // where we draw the text
    private static final float BASELINE_FROM_TOP = PAGE_H - BASELINE_PDF_Y; // 81.89, top-left origin
    private static final float FONT_SIZE = 12f;
    private static final float TEXT_X = 70f;
    private static final int DPI = 150;

    @Test
    void scanRegionsRedactAndRemoveThePii() throws Exception {
        byte[] pdf = onePageWith(EMAIL);

        PiiFinding email = find(PiiScanner.scanBytes(pdf), PiiType.EMAIL);
        assertEquals(1, email.occurrences());
        assertFalse(email.regions().isEmpty(), "value finding must carry a region");
        PiiRegion box = email.regions().get(0);

        // (2) GEOMETRY GUARD — the box covers the glyph BODY, above the baseline, and drops a
        // little below it for descenders. The old buggy box started AT the baseline (y == 81.89)
        // and ran downward, so top >= baseline; here top must be clearly above the baseline.
        double top = box.y();
        double bottom = box.y() + box.height();
        assertTrue(top < BASELINE_FROM_TOP - 3,
            "box top must sit above the baseline to cover glyph bodies: top=" + top
                + " baselineFromTop=" + BASELINE_FROM_TOP);
        assertTrue(bottom > BASELINE_FROM_TOP,
            "box must reach the baseline (cover descenders): bottom=" + bottom);
        assertTrue(bottom < BASELINE_FROM_TOP + FONT_SIZE * 0.8,
            "box must not sag far below the baseline into the next line: bottom=" + bottom);
        assertTrue(box.width() > 40, "box spans the whole address: width=" + box.width());

        // Redact using EXACTLY what the scanner produced.
        byte[] redacted = PdfRedactor.executeBytes(pdf, List.of(toRedact(box)), DPI);

        // (1) TEXT PROOF — the value is gone from the extracted text of the redacted output.
        String text = normalise(extractText(redacted));
        assertFalse(text.contains(normalise(EMAIL)),
            "redacted output must not leak the PII value; extracted=\"" + text + "\"");

        // (3) PIXEL PROOF — the redacted page is solid black exactly where the glyph ink was.
        // Sample heights ABOVE the baseline (where the letters actually sit and where the old
        // underline box left white). Any point inside the drawn rectangle renders black.
        try (PDDocument doc = Loader.loadPDF(redacted)) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, DPI);
            double s = DPI / 72.0;
            double midX = box.x() + box.width() * 0.5;
            for (double aboveBaseline : new double[]{2, 5, 8}) {          // pt above the baseline
                double yPt = BASELINE_FROM_TOP - aboveBaseline;
                assertTrue(isBlack(img, midX * s, yPt * s),
                    "glyph area must be blacked out at " + aboveBaseline + "pt above baseline");
            }
            // And redaction stays local — the top of the page is untouched white.
            assertFalse(isBlack(img, midX * s, 30 * s), "unrelated area must remain unpainted");
        }
    }

    @Test
    void cleanTextIsNeverRedacted() throws Exception {
        String clean = "The quarterly report contains only aggregated figures.";
        byte[] pdf = onePageWith(clean);

        PiiScanResult r = PiiScanner.scanBytes(pdf);
        assertEquals(0, r.totalFindings(), "control document has no PII: " + r.findings());

        // Nothing to redact → the page is copied through and its text survives intact.
        byte[] out = PdfRedactor.executeBytes(pdf, List.of(), DPI);
        assertTrue(normalise(extractText(out)).contains(normalise(clean)),
            "clean text must be preserved when there is nothing to redact");
    }

    // --- helpers -----------------------------------------------------------

    private static RedactRegion toRedact(PiiRegion r) {
        return new RedactRegion(r.page(), r.x(), r.y(), r.width(), r.height());
    }

    private static boolean isBlack(BufferedImage img, double px, double py) {
        int x = Math.max(0, Math.min(img.getWidth() - 1, (int) Math.round(px)));
        int y = Math.max(0, Math.min(img.getHeight() - 1, (int) Math.round(py)));
        int rgb = img.getRGB(x, y);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 < 40;
    }

    private static String normalise(String s) {
        return s.toLowerCase().replaceAll("\\s+", "");
    }

    private static PiiFinding find(PiiScanResult r, PiiType type) {
        Optional<PiiFinding> f = r.findings().stream().filter(x -> x.type() == type).findFirst();
        assertTrue(f.isPresent(), "expected a " + type + " finding in " + r.findings());
        return f.get();
    }

    private static String extractText(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static byte[] onePageWith(String line) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), FONT_SIZE);
                cs.newLineAtOffset(TEXT_X, BASELINE_PDF_Y);
                cs.showText(line);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
