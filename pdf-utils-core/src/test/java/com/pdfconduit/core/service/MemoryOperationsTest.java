package com.pdfconduit.core.service;

import com.pdfconduit.core.operations.PdfMerger;
import com.pdfconduit.core.operations.PdfRotator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryOperationsTest {

    private static byte[] pdfBytes(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] pngBytes() throws IOException {
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) { return doc.getNumberOfPages(); }
    }

    @Test
    void toPdfBytesPassesThroughPdf() throws Exception {
        byte[] pdf = pdfBytes(2);
        assertSame(pdf, MemoryOperations.toPdfBytes(pdf, "in.pdf"));
    }

    @Test
    void toPdfBytesConvertsImage() throws Exception {
        byte[] pdf = MemoryOperations.toPdfBytes(pngBytes(), "pic.png");
        assertEquals(1, pageCount(pdf));
    }

    @Test
    void runSingleRoutesAndRuns() throws Exception {
        byte[] out = MemoryOperations.runSingle(pngBytes(), "pic.png",
            pdf -> PdfRotator.executeBytes(pdf, com.pdfconduit.core.model.PageRange.ALL, 90));
        try (PDDocument doc = Loader.loadPDF(out)) {
            assertEquals(90, doc.getPage(0).getRotation());
        }
    }

    @Test
    void runBatchNamesOutputsFromOperationType() throws Exception {
        List<NamedBytes> outputs = MemoryOperations.runBatch(
            OperationType.ROTATE,
            List.of(pdfBytes(1), pdfBytes(2)),
            List.of("a.pdf", "b.pdf"),
            pdf -> PdfRotator.executeBytes(pdf, com.pdfconduit.core.model.PageRange.ALL, 180));
        assertEquals(2, outputs.size());
        assertEquals("a_rotated.pdf", outputs.get(0).filename());
        assertEquals("b_rotated.pdf", outputs.get(1).filename());
        assertEquals(2, pageCount(outputs.get(1).data()));
    }

    @Test
    void runReduceMergesAndNames() throws Exception {
        NamedBytes out = MemoryOperations.runReduce(
            OperationType.MERGE,
            List.of(pdfBytes(2), pdfBytes(3)),
            List.of("first.pdf", "second.pdf"),
            PdfMerger::executeBytes);
        assertEquals("first_merged.pdf", out.filename());
        assertEquals(5, pageCount(out.data()));
    }

    // --- batch failures: name the file, keep the good results --------------

    /** A PDF nothing can open without the password — the classic mid-batch spoiler. */
    private static byte[] encryptedPdf() throws Exception {
        return com.pdfconduit.core.operations.PdfProtector.executeBytes(pdfBytes(1), "secret", null);
    }

    private static byte[] rotate(byte[] pdf) throws Exception {
        return PdfRotator.executeBytes(pdf, com.pdfconduit.core.model.PageRange.ALL, 90);
    }

    @Test
    void runBatchFailureNamesTheOffendingFile() throws Exception {
        var e = assertThrows(com.pdfconduit.core.exception.PdfOperationException.class,
            () -> MemoryOperations.runBatch(
                OperationType.ROTATE,
                List.of(pdfBytes(1), encryptedPdf()),
                List.of("good.pdf", "locked.pdf"),
                MemoryOperationsTest::rotate));
        // Without the name the user has to bisect the upload to find file #2.
        assertTrue(e.getMessage().startsWith("locked.pdf: "), e.getMessage());
        assertTrue(e.getMessage().contains("password-protected"), e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    void runReduceFailureNamesTheOffendingFile() throws Exception {
        var e = assertThrows(com.pdfconduit.core.exception.PdfOperationException.class,
            () -> MemoryOperations.runReduce(
                OperationType.MERGE,
                List.of(pdfBytes(1), "not a pdf at all".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                List.of("good.pdf", "broken.pdf"),
                PdfMerger::executeBytes));
        assertTrue(e.getMessage().startsWith("broken.pdf: "), e.getMessage());
    }

    @Test
    void mapPartialKeepsTheGoodFilesAndReportsTheBadOne() throws Exception {
        List<NamedBytes> inputs = List.of(
            new NamedBytes("a.pdf", pdfBytes(1)),
            new NamedBytes("locked.pdf", encryptedPdf()),
            new NamedBytes("b.pdf", pdfBytes(2)));

        BatchOutcome outcome = MemoryOperations.mapPartial(inputs,
            in -> List.of(new NamedBytes(MemoryOperations.outputName(OperationType.ROTATE, in.filename()),
                MemoryOperations.runSingle(in.data(), in.filename(), MemoryOperationsTest::rotate))));

        assertTrue(outcome.partial());
        assertEquals(2, outcome.outputs().size());
        assertEquals("a_rotated.pdf", outcome.outputs().get(0).filename());
        assertEquals("b_rotated.pdf", outcome.outputs().get(1).filename());
        assertEquals(1, outcome.failures().size());
        assertEquals("locked.pdf", outcome.failures().get(0).filename());
        assertTrue(outcome.failures().get(0).message().contains("password-protected"),
            outcome.failures().get(0).message());
    }

    @Test
    void mapPartialWithNoFailuresIsAPlainBatch() throws Exception {
        BatchOutcome outcome = MemoryOperations.mapPartial(
            List.of(new NamedBytes("a.pdf", pdfBytes(1)), new NamedBytes("b.pdf", pdfBytes(2))),
            in -> List.of(new NamedBytes(in.filename(), in.data())));
        assertFalse(outcome.partial());
        assertEquals(2, outcome.outputs().size());
        assertTrue(outcome.failures().isEmpty());
    }

    @Test
    void mapPartialThrowsWhenEveryInputFails() throws Exception {
        List<NamedBytes> inputs = List.of(
            new NamedBytes("locked1.pdf", encryptedPdf()),
            new NamedBytes("locked2.pdf", encryptedPdf()));

        // An empty archive would be a worse answer than an error — still fail, but named.
        var e = assertThrows(com.pdfconduit.core.exception.PdfOperationException.class,
            () -> MemoryOperations.mapPartial(inputs,
                in -> List.of(new NamedBytes(in.filename(),
                    MemoryOperations.runSingle(in.data(), in.filename(), MemoryOperationsTest::rotate)))));
        assertTrue(e.getMessage().startsWith("locked1.pdf: "), e.getMessage());
    }

    @Test
    void mapPartialDoesNotSwallowABadPageRange() throws Exception {
        // A wrong parameter is the caller's mistake, not one file's problem: it must fail the request.
        assertThrows(com.pdfconduit.core.exception.InvalidPageRangeException.class,
            () -> MemoryOperations.mapPartial(
                List.of(new NamedBytes("a.pdf", pdfBytes(1))),
                in -> { throw new com.pdfconduit.core.exception.InvalidPageRangeException("9-9"); }));
    }

    @Test
    void runMultiNamesEachPart() throws Exception {
        List<NamedBytes> parts = MemoryOperations.runMulti(
            OperationType.EXTRACT, pdfBytes(3), "doc.pdf",
            pdf -> com.pdfconduit.core.operations.PdfSplitter.separateBytes(
                pdf, com.pdfconduit.core.model.PageRange.ALL));
        assertEquals(3, parts.size());
        assertTrue(parts.get(0).filename().startsWith("doc_extracted"));
    }
}
