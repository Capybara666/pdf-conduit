package com.pdfconduit.core.pipeline;

import com.pdfconduit.core.exception.PdfUnrecoverableException;
import com.pdfconduit.core.operations.PdfProtector;
import com.pdfconduit.core.operations.PdfRepairer;
import com.pdfconduit.core.service.NamedBytes;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a source node accepts. The in-memory pipeline gates every uploaded source twice — by magic
 * bytes, and by the host's document guard — and two nodes exist precisely for uploads that fail one
 * of those gates: REPAIR (bytes that are structurally wrong) and UNLOCK (bytes nobody can open).
 * A gate that refuses them refuses the one input class the node is for, before the node can run.
 *
 * <p>These tests pin both halves: such an upload must reach its node, and an upload that is
 * genuinely not a PDF must still be refused at the source with the same clear message.
 *
 * <p>Every fixture is built programmatically — no binary blobs are committed.
 */
class PipelineSourceGateTest {

    @TempDir Path tmp;

    // --- fixtures ---------------------------------------------------------

    /** A healthy {@code pages}-page PDF, each page carrying identifiable text. */
    private static byte[] healthy(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Marker page " + (i + 1));
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Prepends junk, so the {@code %PDF-} header is no longer at offset 0. */
    private static byte[] leadingJunk(byte[] pdf, int junkBytes) {
        byte[] junk = new byte[junkBytes];
        Arrays.fill(junk, (byte) 'X');
        byte[] out = new byte[junk.length + pdf.length];
        System.arraycopy(junk, 0, out, 0, junk.length);
        System.arraycopy(pdf, 0, out, junk.length, pdf.length);
        return out;
    }

    /** Chops off the xref table, trailer, startxref and {@code %%EOF} — a truncated download. */
    private static byte[] truncatedTrailer(byte[] pdf) {
        int sx = lastIndexOf(pdf, "startxref");
        assertTrue(sx > 0, "fixture: no startxref in the generated PDF");
        int xref = lastIndexOf(Arrays.copyOf(pdf, sx), "xref");
        return Arrays.copyOf(pdf, xref > 0 ? xref : sx);
    }

    /** Drops everything before the first object, so no {@code %PDF-} header survives at all. */
    private static byte[] headerRemoved(byte[] pdf) {
        int at = indexOf(pdf, " 0 obj", 0);
        assertTrue(at > 0, "fixture: no object marker in the generated PDF");
        int start = at;
        while (start > 0 && pdf[start - 1] >= '0' && pdf[start - 1] <= '9') start--;
        assertTrue(start > 0, "fixture: object marker has no number");
        return Arrays.copyOfRange(pdf, start, pdf.length);
    }

    private static byte[] png() throws IOException {
        BufferedImage img = new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 60, 40);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static int indexOf(byte[] data, String needle, int from) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = Math.max(0, from); i <= data.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) if (data[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static int lastIndexOf(byte[] data, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = data.length - n.length; i >= 0; i--) {
            for (int j = 0; j < n.length; j++) if (data[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }

    // --- helpers ----------------------------------------------------------

    /** source → one node → terminal. */
    private static PipelineModel graph(NodeKind kind) {
        PipelineModel m = new PipelineModel();
        m.nodes.add(new PipelineNode("s", NodeKind.SOURCE, 0, 0));
        m.nodes.add(new PipelineNode("n", kind, 0, 0));
        m.connections.add(new Connection("s", "n"));
        return m;
    }

    private static List<NamedBytes> run(PipelineModel m, byte[] upload) throws Exception {
        Map<String, List<NamedBytes>> out = PipelineExecutor.runInMemory(
            m, node -> node.id.equals("s") ? List.of(upload) : List.of(), null);
        return out.get("n");
    }

    private static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) { return doc.getNumberOfPages(); }
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) { return new PDFTextStripper().getText(doc); }
    }

    /** Ground truth that the repaired result is really sound, not merely non-empty. */
    private void assertParsesStrictly(byte[] pdf, String what) throws IOException {
        Path f = tmp.resolve(what + ".pdf");
        Files.write(f, pdf);
        assertTrue(PdfRepairer.isStructurallySound(f), what + " must parse strictly after repair");
    }

    // --- damaged PDFs must reach a Repair node ----------------------------

    @Test
    void junkBeforeTheHeaderReachesTheRepairNode() throws Exception {
        byte[] damaged = leadingJunk(healthy(2), 23);
        Path onDisk = tmp.resolve("damaged-junk.pdf");
        Files.write(onDisk, damaged);
        assertFalse(PdfRepairer.isStructurallySound(onDisk), "fixture is not actually damaged");

        List<NamedBytes> out = run(graph(NodeKind.REPAIR), damaged);

        assertEquals(1, out.size());
        byte[] repaired = out.get(0).data();
        assertEquals(0x25, repaired[0] & 0xFF, "the rebuilt file must start with '%'");
        assertEquals(2, pageCount(repaired));
        assertTrue(textOf(repaired).contains("Marker page 2"), "content must survive the repair");
        assertParsesStrictly(repaired, "junk-repaired");
    }

    @Test
    void aTruncatedTrailerReachesTheRepairNode() throws Exception {
        byte[] damaged = truncatedTrailer(healthy(2));
        Path onDisk = tmp.resolve("damaged-truncated.pdf");
        Files.write(onDisk, damaged);
        assertFalse(PdfRepairer.isStructurallySound(onDisk), "fixture is not actually damaged");

        byte[] repaired = run(graph(NodeKind.REPAIR), damaged).get(0).data();

        assertEquals(2, pageCount(repaired));
        assertTrue(textOf(repaired).contains("Marker page 1"));
        assertParsesStrictly(repaired, "truncated-repaired");
    }

    /**
     * The documented edge of the rule: with no {@code %PDF-} header <em>anywhere</em>, the data is
     * refused at the source — and that costs nothing, because PDFBox itself cannot read such a file,
     * so Repair reports it unrecoverable too. Both halves are asserted, so the day the repairer
     * learns to synthesise a header this test says exactly what has to change.
     */
    @Test
    void aFileWithNoHeaderAtAllIsRefusedBecauseNothingCanReadItAnyway() throws Exception {
        byte[] headerless = headerRemoved(healthy(1));
        assertNotEquals(0x25, headerless[0] & 0xFF, "fixture must not start with '%'");

        assertThrows(PdfUnrecoverableException.class, () -> PdfRepairer.executeBytes(headerless),
            "repair itself cannot rebuild a header-less file");

        PipelineException e = assertThrows(PipelineException.class,
            () -> run(graph(NodeKind.REPAIR), headerless));
        assertTrue(e.getMessage().contains("unsupported data"), e.getMessage());
    }

    // --- non-PDF uploads must still be refused, with the existing message ---

    @Test
    void randomBytesAreStillRejectedAtTheSource() throws Exception {
        byte[] noise = new byte[4096];
        new Random(42).nextBytes(noise);
        noise[0] = 0x00;   // never accidentally a known magic

        PipelineException e = assertThrows(PipelineException.class,
            () -> run(graph(NodeKind.REPAIR), noise));
        assertTrue(e.getMessage().contains("unsupported data"), e.getMessage());
    }

    @Test
    void aZipLikeOfficeUploadIsStillRejectedAtTheSource() throws Exception {
        byte[] docx = new byte[512];
        docx[0] = 'P'; docx[1] = 'K'; docx[2] = 0x03; docx[3] = 0x04;

        PipelineException e = assertThrows(PipelineException.class,
            () -> run(graph(NodeKind.REPAIR), docx));
        assertTrue(e.getMessage().contains("unsupported data"), e.getMessage());
    }

    @Test
    void aPdfHeaderFarBeyondTheSniffWindowIsStillRejected() throws Exception {
        // The lenient sniff is bounded: a header buried kilobytes deep is not a damaged PDF,
        // it is a file that happens to contain one.
        byte[] tooDeep = leadingJunk(healthy(1), 4096);

        PipelineException e = assertThrows(PipelineException.class,
            () -> run(graph(NodeKind.REPAIR), tooDeep));
        assertTrue(e.getMessage().contains("unsupported data"), e.getMessage());
    }

    @Test
    void aPngIsStillClassifiedAsAnImageNotAsADamagedPdf() throws Exception {
        byte[] image = png();

        // Routed into Repair, an image is converted to a one-page PDF first (image classification),
        // which is exactly what a mis-classification as "damaged PDF bytes" would NOT produce.
        byte[] out = run(graph(NodeKind.REPAIR), image).get(0).data();
        assertEquals(1, pageCount(out));

        // And the ordinary image path is untouched.
        byte[] asPdf = run(graph(NodeKind.IMAGES_TO_PDF), image).get(0).data();
        assertEquals(1, pageCount(asPdf));
    }

    // --- the host guard must not close the source gate on UNLOCK ----------

    /**
     * A stand-in for a real host's guard (the web backend's {@code PipelineLimitsGuard}): it opens
     * every source document to apply a page ceiling — which is exactly what an encrypted upload
     * cannot survive.
     */
    private static PipelineGuard pageCap(int maxPages) {
        return new PipelineGuard() {
            @Override
            public void checkDocument(byte[] pdf) throws com.pdfconduit.core.exception.PdfOperationException {
                try (com.pdfconduit.core.util.LoadedPdf lp = com.pdfconduit.core.util.LoadedPdf.open(pdf)) {
                    checkPageCount(lp.pageCount());
                } catch (IOException e) {
                    throw new com.pdfconduit.core.exception.PdfOperationException(
                        "Cannot read PDF: " + e.getMessage(), e);
                }
            }

            @Override
            public void checkPageCount(int pages) throws com.pdfconduit.core.exception.PdfOperationException {
                if (pages > maxPages) {
                    throw new com.pdfconduit.core.exception.PdfOperationException(
                        "PDF exceeds the maximum page count (" + maxPages + ").");
                }
            }
        };
    }

    private static List<NamedBytes> runGuarded(PipelineModel m, byte[] upload, PipelineGuard guard)
            throws Exception {
        return PipelineExecutor.runInMemory(
            m, node -> node.id.equals("s") ? List.of(upload) : List.of(),
            null, guard, null).get("n");
    }

    @Test
    void anEncryptedUploadReachesTheUnlockNodeUnderAHostGuard() throws Exception {
        byte[] encrypted = PdfProtector.executeBytes(healthy(2), "secret", null);

        PipelineModel m = graph(NodeKind.UNLOCK);
        m.nodes.get(1).password = "secret";
        byte[] unlocked = runGuarded(m, encrypted, pageCap(500)).get(0).data();

        assertEquals(2, pageCount(unlocked));
        assertTrue(textOf(unlocked).contains("Marker page 2"));
    }

    @Test
    void unlockingDoesNotSmuggleAPageBombPastTheCeiling() throws Exception {
        // The ceiling is not lost by letting an unopenable upload through the source: it is applied
        // to the decrypted result instead, which is the only readable form of it.
        byte[] encrypted = PdfProtector.executeBytes(healthy(3), "secret", null);

        PipelineModel m = graph(NodeKind.UNLOCK);
        m.nodes.get(1).password = "secret";
        Exception e = assertThrows(Exception.class, () -> runGuarded(m, encrypted, pageCap(1)));
        assertTrue(String.valueOf(e.getMessage()).contains("maximum page count"), e.toString());
    }

    @Test
    void anEncryptedUploadStillFailsClearlyOnANodeThatCannotOpenIt() throws Exception {
        byte[] encrypted = PdfProtector.executeBytes(healthy(1), "secret", null);

        Exception e = assertThrows(Exception.class,
            () -> runGuarded(graph(NodeKind.COMPRESS), encrypted, pageCap(500)));
        assertTrue(String.valueOf(e.getMessage()).contains("password-protected"), e.toString());
    }

    @Test
    void aDamagedUploadIsStillPageCappedAtTheSource() throws Exception {
        // The lenient type gate does not weaken the host ceiling: a readable-but-damaged upload is
        // still counted and refused before any node runs.
        byte[] damaged = leadingJunk(healthy(3), 23);

        Exception e = assertThrows(Exception.class,
            () -> runGuarded(graph(NodeKind.REPAIR), damaged, pageCap(1)));
        assertTrue(String.valueOf(e.getMessage()).contains("maximum page count"), e.toString());
    }

    // --- healthy uploads ---------------------------------------------------

    @Test
    void healthyPdfsBehaveExactlyAsBefore() throws Exception {
        byte[] good = healthy(3);

        byte[] repaired = run(graph(NodeKind.REPAIR), good).get(0).data();
        assertEquals(3, pageCount(repaired));
        assertTrue(textOf(repaired).contains("Marker page 3"));

        PipelineModel m = graph(NodeKind.ROTATE);
        m.nodes.get(1).angle = 90;
        byte[] rotated = run(m, good).get(0).data();
        assertEquals(3, pageCount(rotated));
        try (PDDocument doc = Loader.loadPDF(rotated)) {
            for (PDPage p : doc.getPages()) assertEquals(90, p.getRotation());
        }
    }
}
