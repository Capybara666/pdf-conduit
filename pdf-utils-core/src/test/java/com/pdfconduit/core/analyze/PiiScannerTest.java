package com.pdfconduit.core.analyze;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PiiScannerTest {

    // Well-known valid test values.
    private static final String EMAIL = "john.doe@example.com";
    private static final String PHONE = "+48 123 456 789";
    private static final String IBAN = "DE89370400440532013000";          // valid mod-97
    private static final String CARD_DISPLAY = "4111 1111 1111 1111";      // valid Luhn
    private static final String CARD_DIGITS = "4111111111111111";
    private static final String PESEL = "90010100009";                    // valid checksum + date
    private static final String NIP = "5260250274";                       // valid checksum

    @Test
    void detectsEveryCategoryAndScoresHigh() throws Exception {
        byte[] pdf = pdf(List.of(List.of(
                "Contact: " + EMAIL,
                "Tel: " + PHONE,
                "IBAN " + IBAN,
                "Card " + CARD_DISPLAY,
                "PESEL " + PESEL,
                "NIP " + NIP,
                "The diagnosis is confidential.")));

        PiiScanResult r = PiiScanner.scanBytes(pdf);

        assertEquals(RiskLevel.HIGH, r.risk());
        assertEquals(1, r.pagesScanned());

        assertType(r, PiiType.EMAIL, PiiCategory.CONTACT);
        assertType(r, PiiType.PHONE, PiiCategory.CONTACT);
        assertType(r, PiiType.IBAN, PiiCategory.FINANCIAL);
        assertType(r, PiiType.CREDIT_CARD, PiiCategory.FINANCIAL);
        assertType(r, PiiType.PESEL, PiiCategory.NATIONAL_ID);
        assertType(r, PiiType.NIP, PiiCategory.NATIONAL_ID);
        assertType(r, PiiType.HEALTH, PiiCategory.SPECIAL_CATEGORY);

        // countsByCategory reflects the distinct findings.
        assertEquals(2, r.countsByCategory().get(PiiCategory.CONTACT));
        assertEquals(2, r.countsByCategory().get(PiiCategory.FINANCIAL));
        assertEquals(2, r.countsByCategory().get(PiiCategory.NATIONAL_ID));
        assertEquals(1, r.countsByCategory().get(PiiCategory.SPECIAL_CATEGORY));
    }

    @Test
    void masksNeverRevealTheFullValue() throws Exception {
        byte[] pdf = pdf(List.of(List.of(
                EMAIL, CARD_DISPLAY, PESEL, IBAN)));

        PiiScanResult r = PiiScanner.scanBytes(pdf);

        String emailMask = find(r, PiiType.EMAIL).maskedSample();
        assertFalse(emailMask.contains(EMAIL), emailMask);

        String cardMask = find(r, PiiType.CREDIT_CARD).maskedSample();
        assertFalse(cardMask.contains(CARD_DIGITS), cardMask);
        assertFalse(cardMask.contains(CARD_DISPLAY), cardMask);
        assertTrue(cardMask.endsWith("1111"), cardMask);   // recognisable last four

        String peselMask = find(r, PiiType.PESEL).maskedSample();
        assertFalse(peselMask.contains(PESEL), peselMask);

        String ibanMask = find(r, PiiType.IBAN).maskedSample();
        assertFalse(ibanMask.contains(IBAN), ibanMask);
    }

    @Test
    void cleanDocumentHasNoFindings() throws Exception {
        byte[] pdf = pdf(List.of(List.of(
                "The quarterly report covers regional sales performance.",
                "All figures are aggregated and contain no personal data.")));

        PiiScanResult r = PiiScanner.scanBytes(pdf);

        assertEquals(0, r.totalFindings());
        assertTrue(r.findings().isEmpty());
        assertEquals(RiskLevel.NONE, r.risk());
    }

    @Test
    void invalidChecksumValuesAreNotReported() throws Exception {
        // Luhn-invalid card, IBAN with a broken checksum, invalid PESEL, invalid NIP.
        byte[] pdf = pdf(List.of(List.of(
                "Card 4111 1111 1111 1112",
                "IBAN DE89370400440532013001",
                "PESEL 90010100008",
                "NIP 5260250275")));

        PiiScanResult r = PiiScanner.scanBytes(pdf);

        assertEquals(0, r.totalFindings(), "invalid values must not be reported: " + r.findings());
        assertEquals(RiskLevel.NONE, r.risk());
    }

    @Test
    void valueFindingsCarryRegionsInRedactCoordinates() throws Exception {
        // The email sits on its own line, drawn at x=70 near the top of the A4 page
        // (baseline y=760 of 841.89). The health keyword is a context signal, not a value.
        byte[] pdf = pdf(List.of(List.of(
                EMAIL,
                "The diagnosis is confidential.")));

        PiiScanResult r = PiiScanner.scanBytes(pdf);

        PiiFinding email = find(r, PiiType.EMAIL);
        assertEquals(1, email.occurrences());
        assertFalse(email.regions().isEmpty(), "value finding must carry at least one region");

        PiiRegion box = email.regions().get(0);
        assertEquals(0, box.page(), "region page is 0-based");
        // Plausible, non-zero box overlapping the drawn text, in points, top-left origin.
        assertTrue(box.width() > 40, "box should span the address: width=" + box.width());
        assertTrue(box.height() > 3 && box.height() < 40, "glyph-height box: " + box.height());
        assertTrue(box.x() >= 40 && box.x() < 200, "starts near the left margin: x=" + box.x());
        assertTrue(box.y() > 0 && box.y() < 200, "sits in the upper text area: y=" + box.y());

        // Special-category keyword flags never carry regions (nothing concrete to black out).
        PiiFinding health = find(r, PiiType.HEALTH);
        assertTrue(health.regions().isEmpty(), "special-category finding must have no regions");
    }

    @Test
    void multipleOccurrencesYieldOneRegionEach() throws Exception {
        // Same email twice on the same page → two occurrences, two distinct regions.
        byte[] pdf = pdf(List.of(List.of(
                "First " + EMAIL,
                "Again " + EMAIL)));

        PiiScanResult r = PiiScanner.scanBytes(pdf);

        PiiFinding email = find(r, PiiType.EMAIL);
        assertEquals(2, email.occurrences());
        assertEquals(2, email.regions().size(), "one region per occurrence");
        // The two occurrences are on different lines → different y.
        double y0 = email.regions().get(0).y();
        double y1 = email.regions().get(1).y();
        assertTrue(Math.abs(y1 - y0) > 5, "occurrences on separate lines: " + y0 + " vs " + y1);
    }

    @Test
    void pageNumbersAreCorrectForMultiPage() throws Exception {
        byte[] pdf = pdf(List.of(
                List.of("Cover page, nothing sensitive here."),
                List.of("Reach me at " + EMAIL)));

        PiiScanResult r = PiiScanner.scanBytes(pdf);

        assertEquals(2, r.pagesScanned());
        assertEquals(2, find(r, PiiType.EMAIL).page());
    }

    // --- direct validator unit tests (precision guarantees) ----------------

    @Test
    void luhnValidator() {
        assertTrue(PiiDetectors.luhnValid(CARD_DIGITS));
        assertFalse(PiiDetectors.luhnValid("4111111111111112"));
    }

    @Test
    void ibanValidator() {
        assertTrue(PiiDetectors.ibanValid(IBAN));
        assertFalse(PiiDetectors.ibanValid("DE89370400440532013001"));
    }

    @Test
    void peselValidator() {
        assertTrue(PiiDetectors.peselValid(PESEL));
        assertFalse(PiiDetectors.peselValid("90010100008"));   // bad checksum
        assertFalse(PiiDetectors.peselValid("90130100001"));   // month 13 → no valid date
    }

    @Test
    void nipValidator() {
        assertTrue(PiiDetectors.nipValid(NIP));
        assertFalse(PiiDetectors.nipValid("5260250275"));
    }

    // --- helpers -----------------------------------------------------------

    private static void assertType(PiiScanResult r, PiiType type, PiiCategory category) {
        PiiFinding f = find(r, type);
        assertEquals(category, f.category(), "category for " + type);
    }

    private static PiiFinding find(PiiScanResult r, PiiType type) {
        Optional<PiiFinding> f = r.findings().stream()
                .filter(x -> x.type() == type).findFirst();
        assertTrue(f.isPresent(), "expected a " + type + " finding in " + r.findings());
        return f.get();
    }

    /** Builds an in-memory PDF; each inner list is one page's lines of text. */
    private static byte[] pdf(List<List<String>> pages) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (List<String> lines : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.setLeading(16);
                    cs.newLineAtOffset(70, 760);
                    for (String line : lines) {
                        cs.showText(line);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
