package com.pdfconduit.core.operations;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.exception.PdfUnrecoverableException;
import com.pdfconduit.core.model.RepairBytesResult;
import com.pdfconduit.core.model.RepairFinding;
import com.pdfconduit.core.model.RepairOptions;
import com.pdfconduit.core.model.RepairResult;
import com.pdfconduit.core.service.MemoryOperations;
import com.pdfconduit.core.service.NamedBytes;
import com.pdfconduit.core.service.OperationType;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repair is only worth anything if the damaged fixtures are really damaged and the output really
 * opens, so every fixture here is built programmatically (no binary blobs) and every claim is
 * checked against the actual output: page count, extracted text, and a strict re-parse.
 */
class PdfRepairerTest {

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

    /**
     * Points {@code startxref} at an offset well past the end of the file. The objects are all still
     * there — only the map to them is a lie, which is the single most common real-world corruption.
     */
    private static byte[] brokenStartxref(byte[] pdf) {
        byte[] copy = pdf.clone();
        int sx = lastIndexOf(copy, "startxref");
        assertTrue(sx > 0, "fixture: no startxref in the generated PDF");
        int i = sx + "startxref".length();
        while (i < copy.length && isSpace(copy[i])) i++;
        int digits = 0;
        while (i + digits < copy.length && copy[i + digits] >= '0' && copy[i + digits] <= '9') digits++;
        assertTrue(digits > 0, "fixture: startxref carries no offset");
        // Same length so nothing downstream shifts — only the value becomes nonsense.
        Arrays.fill(copy, i, i + digits, (byte) '9');
        return copy;
    }

    /** Chops off the xref table, trailer, startxref and %%EOF — a classic truncated download. */
    private static byte[] truncatedTrailer(byte[] pdf) {
        int sx = lastIndexOf(pdf, "startxref");
        assertTrue(sx > 0, "fixture: no startxref in the generated PDF");
        int xref = lastIndexOf(Arrays.copyOf(pdf, sx), "xref");
        int cut = xref > 0 ? xref : sx;
        return Arrays.copyOf(pdf, cut);
    }

    /** Prepends junk, so the %PDF- header is no longer at offset 0 (mail/HTTP preamble damage). */
    private static byte[] leadingJunk(byte[] pdf) {
        byte[] junk = "SOME GARBAGE PREAMBLE\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[junk.length + pdf.length];
        System.arraycopy(junk, 0, out, 0, junk.length);
        System.arraycopy(pdf, 0, out, junk.length, pdf.length);
        return out;
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\r' || b == '\n' || b == '\t';
    }

    private static int lastIndexOf(byte[] data, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = data.length - n.length; i >= 0; i--) {
            for (int j = 0; j < n.length; j++) {
                if (data[i + j] != n[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    // --- a healthy file ---------------------------------------------------

    @Test
    void healthyFileIsReportedUndamagedAndStaysOpenable() throws Exception {
        byte[] in = healthy(3);
        RepairBytesResult r = PdfRepairer.executeBytes(in);

        assertFalse(r.wasDamaged(), "a PDFBox-written PDF must not be called damaged");
        assertFalse(r.recovered(), "nothing was broken, so nothing was recovered");
        assertEquals(List.of(), r.findings());
        assertEquals(3, r.pageCount());
        assertEquals(in.length, r.originalBytes());
        assertEquals(r.bytes().length, r.resultBytes());

        assertEquals(3, pageCount(r.bytes()));
        assertTrue(textOf(r.bytes()).contains("Marker page 3"), "content must survive the rewrite");
    }

    // --- damaged files ----------------------------------------------------

    @Test
    void brokenStartxrefIsDetectedRecoveredAndReported() throws Exception {
        byte[] good = healthy(2);
        byte[] bad = brokenStartxref(good);

        // Ground truth that the fixture really is broken: it fails a strict parse on disk.
        Path onDisk = tmp.resolve("broken-startxref.pdf");
        Files.write(onDisk, bad);
        assertFalse(PdfRepairer.isStructurallySound(onDisk), "fixture is not actually damaged");

        RepairBytesResult r = PdfRepairer.executeBytes(bad);

        assertTrue(r.wasDamaged(), "a startxref pointing past EOF is damage");
        assertTrue(r.recovered(), "the rebuilt file must parse strictly");
        assertTrue(r.findings().contains(RepairFinding.STARTXREF_INVALID),
            "expected startxref-invalid, got " + r.findings());
        assertTrue(r.findings().contains(RepairFinding.XREF_REBUILT),
            "expected xref-rebuilt, got " + r.findings());
        assertFalse(r.findings().contains(RepairFinding.REBUILD_INCOMPLETE));

        assertEquals(2, r.pageCount());
        assertEquals(2, pageCount(r.bytes()));
        assertTrue(textOf(r.bytes()).contains("Marker page 2"), "content must survive the repair");
    }

    @Test
    void truncatedTrailerIsRecovered() throws Exception {
        byte[] bad = truncatedTrailer(healthy(2));
        RepairBytesResult r = PdfRepairer.executeBytes(bad);

        assertTrue(r.wasDamaged());
        assertTrue(r.recovered(), "findings were " + r.findings());
        assertTrue(r.findings().contains(RepairFinding.EOF_MISSING),
            "expected eof-missing, got " + r.findings());
        assertEquals(2, pageCount(r.bytes()));
        assertTrue(textOf(r.bytes()).contains("Marker page 1"));
    }

    @Test
    void junkBeforeTheHeaderIsDetectedAndRepaired() throws Exception {
        byte[] bad = leadingJunk(healthy(1));
        RepairBytesResult r = PdfRepairer.executeBytes(bad);

        assertTrue(r.wasDamaged());
        assertTrue(r.findings().contains(RepairFinding.HEADER_OFFSET),
            "expected header-offset, got " + r.findings());
        // The startxref offsets are still valid relative to the header, so they must NOT be flagged.
        assertFalse(r.findings().contains(RepairFinding.STARTXREF_INVALID),
            "header-relative offsets are valid; got " + r.findings());
        assertTrue(r.recovered(), "findings were " + r.findings());
        assertEquals(0x25, r.bytes()[0] & 0xFF, "the rebuilt file must start with '%'");
        assertEquals(1, pageCount(r.bytes()));
    }

    // --- hopeless input ---------------------------------------------------

    @Test
    void nonPdfDataFailsWithAClearUnrecoverableError() {
        byte[] junk = "this is definitely not a pdf".getBytes(StandardCharsets.UTF_8);
        PdfUnrecoverableException e =
            assertThrows(PdfUnrecoverableException.class, () -> PdfRepairer.executeBytes(junk));
        assertTrue(e instanceof PdfOperationException, "must stay in the operation-exception family");
        assertTrue(e.getMessage().toLowerCase().contains("could not be repaired"), e.getMessage());
    }

    @Test
    void emptyInputFailsWithAClearUnrecoverableError() {
        assertThrows(PdfUnrecoverableException.class, () -> PdfRepairer.executeBytes(new byte[0]));
    }

    @Test
    void headerOnlyGarbageFailsInsteadOfEmittingAnEmptyPdf() {
        // Looks like a PDF (right magic bytes) but carries no objects at all — repair must refuse
        // rather than hand back a plausible-looking empty document.
        byte[] fake = "%PDF-1.7\nnothing to see here\n".getBytes(StandardCharsets.US_ASCII);
        assertThrows(PdfUnrecoverableException.class, () -> PdfRepairer.executeBytes(fake));
    }

    // --- Path API ---------------------------------------------------------

    @Test
    void pathVariantWritesARepairedFileThatParsesStrictly() throws Exception {
        Path src = tmp.resolve("broken.pdf");
        Files.write(src, brokenStartxref(healthy(2)));
        Path out = tmp.resolve("out/fixed.pdf");

        assertFalse(PdfRepairer.isStructurallySound(src), "fixture must not be sound to begin with");

        RepairResult r = PdfRepairer.execute(new RepairOptions(src, out));

        assertTrue(Files.exists(out));
        assertTrue(r.wasDamaged());
        assertTrue(r.recovered());
        assertEquals(2, r.pageCount());
        assertEquals(Files.size(out), r.resultBytes());
        assertTrue(PdfRepairer.isStructurallySound(out), "the written file must parse strictly");
    }

    // --- catalog / in-memory plumbing -------------------------------------

    @Test
    void memoryOperationsRunsRepairAsAMapBatchWithTheCatalogSuffix() throws Exception {
        byte[] one = brokenStartxref(healthy(1));
        byte[] two = healthy(2);

        List<NamedBytes> out = MemoryOperations.runBatch(OperationType.REPAIR,
            List.of(one, two), List.of("broken.pdf", "fine.pdf"),
            pdf -> PdfRepairer.executeBytes(pdf).bytes());

        assertEquals(2, out.size());
        assertEquals("broken_repaired.pdf", out.get(0).filename());
        assertEquals("fine_repaired.pdf", out.get(1).filename());
        assertEquals(1, pageCount(out.get(0).data()));
        assertEquals(2, pageCount(out.get(1).data()));
    }

    @Test
    void catalogEntryIsAPlainSingleOutputMapOperation() {
        assertEquals("repair", OperationType.REPAIR.id());
        assertEquals("_repaired", OperationType.REPAIR.suffix());
        assertFalse(OperationType.REPAIR.multiOutput());
        assertEquals(com.pdfconduit.core.service.Cardinality.MAP, OperationType.REPAIR.cardinality());
    }
}
