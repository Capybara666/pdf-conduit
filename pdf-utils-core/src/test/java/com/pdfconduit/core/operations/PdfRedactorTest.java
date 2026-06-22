package com.pdfconduit.core.operations;

import com.pdfconduit.core.model.RedactOptions;
import com.pdfconduit.core.model.RedactRegion;
import com.pdfconduit.core.model.RedactResult;
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
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfRedactorTest {

    @TempDir Path tmp;

    @Test
    void rasterisedPageLosesItsText_othersKeepIt() throws Exception {
        Path src = createPdf("SECRET", "PUBLIC");   // A4 pages, text at baseline (100,700)
        Path out = tmp.resolve("redacted.pdf");

        // Cover the whole first page; leave the second alone.
        RedactResult result = PdfRedactor.execute(new RedactOptions(
            src, List.of(new RedactRegion(0, 0, 0, 595, 842)), 150, out));

        assertEquals(out, result.output());
        assertEquals(1, result.redactedPages());
        assertEquals(1, result.redactedRegions());

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(2, doc.getNumberOfPages(), "page count preserved");
            assertFalse(textOfPage(doc, 1).contains("SECRET"), "redacted text must be gone");
            assertTrue(textOfPage(doc, 2).contains("PUBLIC"), "untouched page keeps its text");
        }
    }

    @Test
    void paintsTheRegionBlack() throws Exception {
        Path src = createPdf("SECRET");
        Path out = tmp.resolve("black.pdf");

        // A box around the text: top-left origin, A4 height 842 ⇒ text baseline 700 is ~142 from top.
        PdfRedactor.execute(new RedactOptions(
            src, List.of(new RedactRegion(0, 90, 120, 200, 40)), 150, out));

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 150);
            // Sample the centre of the redacted region (points → pixels at 150 DPI).
            double s = 150.0 / 72.0;
            int px = (int) ((90 + 100) * s), py = (int) ((120 + 20) * s);
            int rgb = img.getRGB(px, py) & 0xFFFFFF;
            assertEquals(0, rgb, "redacted region centre should be solid black");
        }
    }

    @Test
    void noRegionsCopiesEveryPageThrough() throws Exception {
        Path src = createPdf("ALPHA", "BETA");
        Path out = tmp.resolve("copy.pdf");

        RedactResult result = PdfRedactor.execute(new RedactOptions(src, List.of(), 150, out));

        assertEquals(0, result.redactedPages());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(2, doc.getNumberOfPages());
            assertTrue(textOfPage(doc, 1).contains("ALPHA"));
            assertTrue(textOfPage(doc, 2).contains("BETA"));
        }
    }

    @Test
    void emptyRectanglesAreIgnored() throws Exception {
        Path src = createPdf("ALPHA");
        Path out = tmp.resolve("empty.pdf");

        RedactResult result = PdfRedactor.execute(new RedactOptions(
            src, List.of(new RedactRegion(0, 10, 10, 0, 50)), 150, out));

        assertEquals(0, result.redactedPages(), "a zero-area rectangle is a no-op");
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertTrue(textOfPage(doc, 1).contains("ALPHA"), "page left untouched, text intact");
        }
    }

    // --- coordinate correctness on rotated / offset-cropbox pages (audit finding L1) ---
    // Regions are in displayed-page points (top-left origin). These verify the painted box
    // lands on the side the user drew, regardless of page rotation or a non-zero cropbox
    // origin — a misplacement on a permanent redaction would be a silent leak.

    @Test
    void redactsTheCorrectHalfOnA90DegRotatedPage() throws Exception {
        Path src = blankPage(PDRectangle.A4, 90, 0, 0);     // displayed = landscape 842 x 595
        assertHalfRedaction(src, 842, 595);
    }

    @Test
    void redactsTheCorrectHalfOnA270DegRotatedPage() throws Exception {
        Path src = blankPage(PDRectangle.A4, 270, 0, 0);
        assertHalfRedaction(src, 842, 595);
    }

    @Test
    void redactsTheCorrectHalfWhenCropBoxOriginIsOffset() throws Exception {
        // mediaBox 700x900, cropBox origin (50,50) size 595x795 — displayed = 595 x 795.
        Path src = blankPage(new PDRectangle(0, 0, 700, 900), 0, 50, 50);
        assertHalfRedaction(src, 595, 795);
    }

    /** Redacts the left half (in displayed points) and asserts the output's left half is black, right white. */
    private void assertHalfRedaction(Path src, float dispW, float dispH) throws Exception {
        Path out = tmp.resolve("half-" + System.nanoTime() + ".pdf");
        PdfRedactor.execute(new RedactOptions(
            src, List.of(new RedactRegion(0, 0, 0, dispW / 2f, dispH)), 150, out));

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 150);
            int w = img.getWidth(), h = img.getHeight();
            // Sample interior points well inside each half, at three heights.
            for (double fy : new double[]{0.25, 0.5, 0.75}) {
                int y = (int) (h * fy);
                assertTrue(isDark(img.getRGB((int) (w * 0.25), y)),
                    "left half should be redacted black at y=" + y);
                assertTrue(isLight(img.getRGB((int) (w * 0.75), y)),
                    "right half should be untouched (white) at y=" + y);
            }
        }
    }

    private static boolean isDark(int rgb)  { return luma(rgb) < 40; }
    private static boolean isLight(int rgb) { return luma(rgb) > 215; }
    private static int luma(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    /** A blank page with the given mediaBox, rotation and cropBox lower-left offset. */
    private Path blankPage(PDRectangle media, int rotation, float cropX, float cropY) throws IOException {
        Path path = tmp.resolve("blank-" + rotation + "-" + System.nanoTime() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(media);
            page.setRotation(rotation);
            if (cropX != 0 || cropY != 0) {
                page.setCropBox(new PDRectangle(cropX, cropY,
                    media.getWidth() - 2 * cropX, media.getHeight() - 2 * cropY));
            }
            doc.addPage(page);
            doc.save(path.toFile());
        }
        return path;
    }

    private static String textOfPage(PDDocument doc, int oneBasedPage) throws IOException {
        PDFTextStripper s = new PDFTextStripper();
        s.setStartPage(oneBasedPage);
        s.setEndPage(oneBasedPage);
        return s.getText(doc);
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
