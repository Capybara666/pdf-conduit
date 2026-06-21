package com.pdfconduit.core.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import com.pdfconduit.core.exception.PdfOperationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OperationRunnerTest {

    @TempDir Path tmp;

    private Path pdf(String name) throws Exception {
        Path p = tmp.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(p.toFile());
        }
        return p;
    }

    private Path png(String name) throws Exception {
        Path p = tmp.resolve(name);
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
        return p;
    }

    @Test
    void outputNameAppliesSuffix() {
        assertEquals("report_compressed.pdf",
            OperationRunner.outputName(OperationType.COMPRESS, Path.of("/x/report.pdf")));
        assertEquals("photo_converted.pdf",
            OperationRunner.outputName(OperationType.IMAGES_TO_PDF, Path.of("/x/photo.png")));
    }

    @Test
    void runConvertsImageInputToPdfAndCleansTheTemp() throws Exception {
        Path img = png("pic.png");
        Path out = tmp.resolve("out.pdf");
        AtomicReference<Path> seen = new AtomicReference<>();
        OperationRunner.run(img, out, (in, o) -> {
            seen.set(in);
            Files.copy(in, o);     // 'in' is a real PDF produced by conversion
            return "ok";
        });
        assertTrue(seen.get().toString().toLowerCase().endsWith(".pdf"));
        assertNotEquals(img, seen.get(), "image input must be converted, not passed through");
        assertTrue(Files.notExists(seen.get()), "converted temp must be cleaned up");
        assertTrue(Files.exists(out));
    }

    @Test
    void runPassesPdfInputThroughWithoutCreatingTemp() throws Exception {
        Path src = pdf("src.pdf");
        Path out = tmp.resolve("out2.pdf");
        AtomicReference<Path> seen = new AtomicReference<>();
        OperationRunner.run(src, out, (in, o) -> { seen.set(in); Files.copy(in, o); return null; });
        assertEquals(src, seen.get());
        assertTrue(Files.exists(src), "a PDF input is never deleted");
    }

    @Test
    void runBatchNamesEachOutputBySuffixAndReportsProgress() throws Exception {
        List<Path> inputs = List.of(pdf("a.pdf"), pdf("b.pdf"));
        Path outDir = tmp.resolve("out");
        List<int[]> reports = new ArrayList<>();
        List<Path> outs = OperationRunner.runBatch(OperationType.ROTATE, inputs, outDir,
            (in, o) -> { Files.copy(in, o); return null; },
            (completed, total) -> reports.add(new int[]{completed, total}));
        assertEquals(List.of(outDir.resolve("a_rotated.pdf"), outDir.resolve("b_rotated.pdf")), outs);
        assertTrue(Files.exists(outDir.resolve("a_rotated.pdf")));
        assertEquals(2, reports.size());
        assertArrayEquals(new int[]{1, 2}, reports.get(0));
        assertArrayEquals(new int[]{2, 2}, reports.get(1));
    }

    @Test
    void runBatchPrefixesPerFileErrorsWithTheFilename() throws Exception {
        List<Path> inputs = List.of(pdf("good.pdf"), pdf("bad.pdf"));
        Path outDir = tmp.resolve("out3");
        PdfOperationException ex = assertThrows(PdfOperationException.class, () ->
            OperationRunner.runBatch(OperationType.COMPRESS, inputs, outDir,
                (in, o) -> {
                    if (in.getFileName().toString().contains("bad")) throw new RuntimeException("boom");
                    Files.copy(in, o);
                    return null;
                }, ProgressSink.NONE));
        assertTrue(ex.getMessage().startsWith("bad.pdf:"), ex.getMessage());
    }
}
