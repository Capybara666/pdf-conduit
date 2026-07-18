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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ground-truth proof for the <b>one-click auto-redact</b> flow (web {@code POST /api/auto-redact}):
 * scan a document with {@link PiiScanner}, collect <em>every</em> concrete-value finding's regions
 * (exactly as {@code WebOperations.autoRedact} does), feed them straight into {@link PdfRedactor},
 * and prove the personal data is actually gone — three independent ways per value:
 *
 * <ol>
 *   <li><b>Text</b>: none of the PII values can be extracted from the redacted output;</li>
 *   <li><b>Pixels</b>: the redacted page is solid black at the exact glyph-ink heights (above each
 *       value's known baseline) — the mis-placed-Y "underline" bug would leave these white;</li>
 *   <li><b>Locality</b>: unrelated whitespace on the page stays white.</li>
 * </ol>
 *
 * <p>Each value is drawn on its own line at a known baseline, so the true vertical ink band is known
 * independently of the code under test. Multiple distinct PII types (email + IBAN) exercise the
 * "aggregate all findings" collection the auto-redact endpoint performs.
 */
class AutoRedactCoverageTest {

    private static final float PAGE_H = PDRectangle.A4.getHeight();
    private static final float FONT_SIZE = 12f;
    private static final float TEXT_X = 70f;
    private static final int DPI = 150;

    /** A PII value drawn on its own line at a known PDF-space baseline. */
    private record Line(String value, float baselinePdfY) {
        float baselineFromTop() {
            return PAGE_H - baselinePdfY;
        }
    }

    @Test
    void autoRedactRemovesEveryDetectedValue() throws Exception {
        // Two distinct, high-precision detector types on their own lines.
        Line email = new Line("john.doe@example.com", 760f);
        Line iban = new Line("DE89 3704 0044 0532 0130 00", 700f);  // valid mod-97 IBAN, grouped
        List<Line> lines = List.of(email, iban);

        byte[] pdf = pageWith(lines);

        PiiScanResult scan = PiiScanner.scanBytes(pdf);
        // Both value types must be detected (email → CONTACT, IBAN → FINANCIAL).
        assertNotNull(findRegion(scan, PiiType.EMAIL), "email must be detected with a region");
        assertNotNull(findRegion(scan, PiiType.IBAN), "IBAN must be detected with a region");

        // COLLECT EVERY finding's regions — the exact aggregate the auto-redact endpoint applies.
        List<RedactRegion> regions = new ArrayList<>();
        for (PiiFinding f : scan.findings()) {
            for (PiiRegion r : f.regions()) {
                regions.add(new RedactRegion(r.page(), r.x(), r.y(), r.width(), r.height()));
            }
        }
        assertFalse(regions.isEmpty(), "auto-redact must collect at least one region");

        byte[] redacted = PdfRedactor.executeBytes(pdf, regions, DPI);

        // (1) TEXT PROOF — no PII value survives in the extracted text of the output.
        String text = normalise(extractText(redacted));
        assertFalse(text.contains(normalise(email.value())), "email leaked in output text: " + text);
        // The IBAN canonical form (no spaces) must not survive either.
        assertFalse(text.contains(normalise(iban.value())), "IBAN leaked in output text: " + text);

        // (2) PIXEL PROOF + (3) LOCALITY — for each value, glyph-ink heights are black, header white.
        try (PDDocument doc = Loader.loadPDF(redacted)) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, DPI);
            double s = DPI / 72.0;
            for (Line line : lines) {
                PiiRegion box = line == email
                    ? findRegion(scan, PiiType.EMAIL)
                    : findRegion(scan, PiiType.IBAN);
                double midX = box.x() + box.width() * 0.5;
                for (double aboveBaseline : new double[]{2, 5, 8}) {
                    double yPt = line.baselineFromTop() - aboveBaseline;
                    assertTrue(isBlack(img, midX * s, yPt * s),
                        "glyph ink for \"" + line.value() + "\" must be blacked out "
                            + aboveBaseline + "pt above its baseline");
                }
            }
            // Untouched header area stays white.
            assertFalse(isBlack(img, TEXT_X * s, 30 * s), "unrelated area must remain unpainted");
        }
    }

    // --- helpers -----------------------------------------------------------

    private static PiiRegion findRegion(PiiScanResult r, PiiType type) {
        return r.findings().stream()
            .filter(f -> f.type() == type && !f.regions().isEmpty())
            .map(f -> f.regions().get(0))
            .findFirst()
            .orElse(null);
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

    private static String extractText(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static byte[] pageWith(List<Line> lines) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), FONT_SIZE);
                for (Line line : lines) {
                    cs.beginText();
                    cs.newLineAtOffset(TEXT_X, line.baselinePdfY());
                    cs.showText(line.value());
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
