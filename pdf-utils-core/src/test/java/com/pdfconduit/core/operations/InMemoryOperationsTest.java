package com.pdfconduit.core.operations;

import com.pdfconduit.core.model.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the in-memory (byte[]) operation API. Inputs are PDFs / images
 * generated in memory with PDFBox / ImageIO; outputs are re-loaded and asserted. Where
 * practical, parity with the Path variant is checked (same page counts etc.).
 */
class InMemoryOperationsTest {

    // --- helpers ----------------------------------------------------------

    private static byte[] pdfBytes(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(100, 700);
                    cs.showText("Page " + (i + 1) + " content");
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] pngBytes(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.CYAN);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) { return doc.getNumberOfPages(); }
    }

    private static PDDocument load(byte[] pdf) throws IOException { return Loader.loadPDF(pdf); }

    // --- merge ------------------------------------------------------------

    @Test
    void mergeBytesConcatenatesAllPages() throws Exception {
        byte[] out = PdfMerger.executeBytes(List.of(pdfBytes(3), pdfBytes(2)));
        assertEquals(5, pageCount(out));
    }

    // --- split ------------------------------------------------------------

    @Test
    void splitCombineBytesSelectsPages() throws Exception {
        byte[] out = PdfSplitter.combineBytes(pdfBytes(5), new PageRange(List.of(1, 3, 5)));
        assertEquals(3, pageCount(out));
    }

    @Test
    void splitSeparateBytesYieldsOnePdfPerPage() throws Exception {
        List<byte[]> parts = PdfSplitter.separateBytes(pdfBytes(4), PageRange.ALL);
        assertEquals(4, parts.size());
        for (byte[] p : parts) assertEquals(1, pageCount(p));
    }

    // --- rotate (parity with Path) ---------------------------------------

    @Test
    void rotateBytesRotatesSelectedPage() throws Exception {
        byte[] out = PdfRotator.executeBytes(pdfBytes(3), new PageRange(List.of(2)), 90);
        try (PDDocument doc = load(out)) {
            assertEquals(90, doc.getPage(1).getRotation());
            assertEquals(0, doc.getPage(0).getRotation());
        }
    }

    // --- arrange ----------------------------------------------------------

    @Test
    void arrangeBytesReordersPages() throws Exception {
        byte[] out = PdfArranger.executeBytes(pdfBytes(3), List.of(3, 1, 2));
        assertEquals(3, pageCount(out));
    }

    @Test
    void arrangeBytesRejectsOutOfRange() throws Exception {
        byte[] pdf = pdfBytes(2);
        assertThrows(Exception.class, () -> PdfArranger.executeBytes(pdf, List.of(5)));
    }

    // --- image to pdf -----------------------------------------------------

    @Test
    void imagesToPdfBytesMakesOnePagePerImage() throws Exception {
        byte[] out = ImageToPdfConverter.executeBytes(
            List.of(pngBytes(100, 80), pngBytes(60, 60)), PageSize.A4);
        assertEquals(2, pageCount(out));
    }

    // --- compress ---------------------------------------------------------

    @Test
    void compressBytesReachesGenerousTarget() throws Exception {
        byte[] input = pdfBytes(3);
        CompressBytesResult r = PdfCompressor.compressBytes(input, 10L * 1024 * 1024);
        assertTrue(r.targetReached());
        assertEquals(input.length, r.originalBytes());
        assertEquals(r.bytes().length, r.resultBytes());
        assertEquals(3, pageCount(r.bytes()));
    }

    @Test
    void compressBytesNeverLargerThanInputWhenTargetUnreachable() throws Exception {
        byte[] input = pdfBytes(3);
        CompressBytesResult r = PdfCompressor.compressBytes(input, 1);   // impossible target
        assertFalse(r.targetReached());
        assertTrue(r.resultBytes() <= r.originalBytes(), "result must never exceed the input");
        assertEquals(3, pageCount(r.bytes()));
    }

    // --- protect + unlock round trip -------------------------------------

    @Test
    void protectThenUnlockBytesRoundTrip() throws Exception {
        byte[] original = pdfBytes(2);
        byte[] protectedPdf = PdfProtector.executeBytes(original, "secret", null);

        // The protected bytes cannot be opened without the password.
        assertThrows(Exception.class, () -> Loader.loadPDF(protectedPdf));

        byte[] unlocked = PdfUnlocker.executeBytes(protectedPdf, "secret");
        assertEquals(2, pageCount(unlocked));   // opens with no password now
    }

    // --- metadata read/edit round trip -----------------------------------

    @Test
    void metadataEditThenReadBytesRoundTrip() throws Exception {
        byte[] edited = PdfMetadataEditor.executeBytes(
            pdfBytes(1), "My Title", "Jane", "Subj", "k1,k2", false);
        PdfMetadata meta = PdfMetadataEditor.readBytes(edited);
        assertEquals("My Title", meta.title());
        assertEquals("Jane", meta.author());
        assertEquals("Subj", meta.subject());
        assertEquals("k1,k2", meta.keywords());

        byte[] stripped = PdfMetadataEditor.executeBytes(edited, null, null, null, null, true);
        PdfMetadata cleared = PdfMetadataEditor.readBytes(stripped);
        assertNull(cleared.title());
        assertNull(cleared.author());
    }

    // --- watermark --------------------------------------------------------

    @Test
    void watermarkBytesTextStampsEveryPage() throws Exception {
        byte[] out = PdfWatermarker.executeBytes(pdfBytes(2), "DRAFT", null, 0.3, 45, 0.7);
        assertEquals(2, pageCount(out));
    }

    // --- redact -----------------------------------------------------------

    @Test
    void redactBytesPreservesPageCount() throws Exception {
        List<RedactRegion> regions = List.of(new RedactRegion(0, 50, 50, 100, 30));
        byte[] out = PdfRedactor.executeBytes(pdfBytes(2), regions, 100);
        assertEquals(2, pageCount(out));
    }

    // --- to images --------------------------------------------------------

    @Test
    void toImagesBytesRendersEachPage() throws Exception {
        List<byte[]> images = PdfToImageConverter.executeBytes(
            pdfBytes(3), ImageFormat.PNG, 72, PageRange.ALL, 0.8f);
        assertEquals(3, images.size());
        for (byte[] img : images) {
            assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(img)));
        }
    }

    // --- to text ----------------------------------------------------------

    @Test
    void toTextBytesExtractsContent() throws Exception {
        String text = PdfTextExporter.extractTextBytes(pdfBytes(2), PageRange.ALL);
        assertTrue(text.contains("Page 1 content"));
        assertTrue(text.contains("Page 2 content"));

        byte[] txt = PdfTextExporter.toTextBytes(pdfBytes(1), TextFormat.TXT, PageRange.ALL);
        assertTrue(new String(txt, java.nio.charset.StandardCharsets.UTF_8).contains("Page 1"));
    }
}
