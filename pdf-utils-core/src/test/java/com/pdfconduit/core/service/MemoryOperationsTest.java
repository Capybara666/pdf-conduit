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
