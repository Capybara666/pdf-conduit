package com.pdfconduit.core.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.service.OperationRunner.BatchOutcome;
import com.pdfconduit.core.service.OperationRunner.OverwritePolicy;
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

    /** An execution that just copies its (already-PDF) input to the output (overwriting, as real ops do). */
    private static final Execution<Void> COPY = (in, o) -> {
        Files.copy(in, o, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return null;
    };

    @Test
    void runBatchNamesEachOutputBySuffixAndReportsProgress() throws Exception {
        List<Path> inputs = List.of(pdf("a.pdf"), pdf("b.pdf"));
        Path outDir = tmp.resolve("out");
        List<int[]> reports = new ArrayList<>();
        BatchOutcome r = OperationRunner.runBatch(OperationType.ROTATE, inputs, outDir, COPY,
            (completed, total) -> reports.add(new int[]{completed, total}), OverwritePolicy.RENAME);
        assertEquals(List.of(outDir.resolve("a_rotated.pdf"), outDir.resolve("b_rotated.pdf")), r.outputs());
        assertTrue(r.failures().isEmpty());
        assertEquals(0, r.renamed());
        assertEquals(2, r.done());
        assertTrue(Files.exists(outDir.resolve("a_rotated.pdf")));
        assertEquals(2, reports.size());
        assertArrayEquals(new int[]{1, 2}, reports.get(0));
        assertArrayEquals(new int[]{2, 2}, reports.get(1));
    }

    @Test
    void runBatchStopsBetweenFilesWhenCancelled() throws Exception {
        List<Path> inputs = List.of(pdf("a.pdf"), pdf("b.pdf"), pdf("c.pdf"));
        Path outDir = tmp.resolve("cancel");
        List<Path> processed = new ArrayList<>();
        // Cancel as soon as the first file is done — the loop should stop before the rest.
        BatchOutcome r = OperationRunner.runBatch(OperationType.ROTATE, inputs, outDir,
            (in, o) -> { Files.copy(in, o); processed.add(in); return null; },
            ProgressSink.NONE, OverwritePolicy.RENAME, () -> !processed.isEmpty());
        assertEquals(1, r.outputs().size(), "only the first file should be produced");
        assertTrue(Files.exists(outDir.resolve("a_rotated.pdf")));
        assertTrue(Files.notExists(outDir.resolve("b_rotated.pdf")));
    }

    // --- A3: one bad file must not abort the batch ------------------------

    @Test
    void runBatchContinuesPastAFailingFileAndCollectsIt() throws Exception {
        List<Path> inputs = List.of(pdf("good.pdf"), pdf("bad.pdf"), pdf("also-good.pdf"));
        Path outDir = tmp.resolve("out3");
        List<int[]> reports = new ArrayList<>();
        BatchOutcome r = OperationRunner.runBatch(OperationType.COMPRESS, inputs, outDir,
            (in, o) -> {
                if (in.getFileName().toString().contains("bad")) throw new RuntimeException("boom");
                Files.copy(in, o);
                return null;
            }, (c, t) -> reports.add(new int[]{c, t}), OverwritePolicy.RENAME);
        // The two good files are still produced; the bad one is recorded, not thrown.
        assertEquals(2, r.outputs().size());
        assertTrue(Files.exists(outDir.resolve("good_compressed.pdf")));
        assertTrue(Files.exists(outDir.resolve("also-good_compressed.pdf")));
        assertEquals(1, r.failures().size());
        assertEquals("bad.pdf", r.failures().get(0).input());
        assertTrue(r.failures().get(0).message().contains("boom"));
        assertEquals(2, r.done());
        assertEquals(3, r.attempted());
        assertEquals(3, reports.size(), "progress is reported for every file, including the failed one");
    }

    // --- A1: honour the overwrite policy on collisions --------------------

    @Test
    void runBatchRenamesExistingOutputUnderRenamePolicy() throws Exception {
        Path a = pdf("a.pdf");
        Path outDir = tmp.resolve("ren");
        Files.createDirectories(outDir);
        Path pre = outDir.resolve("a_rotated.pdf");
        Files.writeString(pre, "KEEP");
        BatchOutcome r = OperationRunner.runBatch(OperationType.ROTATE, List.of(a), outDir, COPY,
            ProgressSink.NONE, OverwritePolicy.RENAME);
        assertEquals(1, r.renamed());
        assertEquals(List.of(outDir.resolve("a_rotated (1).pdf")), r.outputs());
        assertEquals("KEEP", Files.readString(pre), "the existing file must be preserved");
    }

    @Test
    void runBatchOverwritesExistingNonInputUnderOverwritePolicy() throws Exception {
        Path a = pdf("a.pdf");
        Path outDir = tmp.resolve("ovr");
        Files.createDirectories(outDir);
        Path pre = outDir.resolve("a_rotated.pdf");
        Files.writeString(pre, "OLD");
        BatchOutcome r = OperationRunner.runBatch(OperationType.ROTATE, List.of(a), outDir, COPY,
            ProgressSink.NONE, OverwritePolicy.OVERWRITE);
        assertEquals(0, r.renamed());
        assertEquals(List.of(pre), r.outputs());
        assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(pre),
            "the existing file must be overwritten with the result");
    }

    // --- A2: never write a result onto one of the inputs ------------------

    @Test
    void runBatchNeverOverwritesAnInputEvenUnderOverwritePolicy() throws Exception {
        // Rotating a.pdf yields "a_rotated.pdf" — which is ALSO an input here.
        Path a = pdf("a.pdf");
        Path collide = pdf("a_rotated.pdf");
        // Output dir is the inputs' own folder, so the naming collides with a real input.
        BatchOutcome r = OperationRunner.runBatch(OperationType.ROTATE, List.of(a, collide), tmp, COPY,
            ProgressSink.NONE, OverwritePolicy.OVERWRITE);
        assertTrue(Files.exists(tmp.resolve("a_rotated (1).pdf")),
            "a.pdf's result must be renamed off the a_rotated.pdf input");
        assertEquals(1, r.renamed());
        assertTrue(r.failures().isEmpty());
    }

    // --- runBatchMulti: multi-output ops also continue past failures ------

    @Test
    void runBatchMultiContinuesPastFailuresAndReportsEveryFile() throws Exception {
        List<Path> inputs = List.of(pdf("a.pdf"), pdf("bad.pdf"), pdf("c.pdf"));
        Path outDir = tmp.resolve("multi");
        List<int[]> reports = new ArrayList<>();
        BatchOutcome r = OperationRunner.runBatchMulti(inputs, outDir,
            (pdf, in) -> {
                if (in.getFileName().toString().contains("bad")) throw new RuntimeException("nope");
                Files.copy(pdf, outDir.resolve(stripExt(in) + ".out"));
            }, (c, t) -> reports.add(new int[]{c, t}), () -> false);
        assertEquals(1, r.failures().size());
        assertEquals("bad.pdf", r.failures().get(0).input());
        assertTrue(r.failures().get(0).message().contains("nope"));
        assertEquals(2, r.done());
        assertEquals(3, r.attempted());
        assertTrue(Files.exists(outDir.resolve("a.out")));
        assertTrue(Files.exists(outDir.resolve("c.out")));
        assertEquals(3, reports.size());
    }

    @Test
    void runBatchMultiStopsBetweenFilesWhenCancelled() throws Exception {
        List<Path> inputs = List.of(pdf("a.pdf"), pdf("b.pdf"), pdf("c.pdf"));
        Path outDir = tmp.resolve("multiCancel");
        List<Path> processed = new ArrayList<>();
        BatchOutcome r = OperationRunner.runBatchMulti(inputs, outDir,
            (pdf, in) -> { Files.copy(pdf, outDir.resolve(stripExt(in) + ".out")); processed.add(in); },
            ProgressSink.NONE, () -> !processed.isEmpty());
        assertEquals(1, r.attempted(), "only the first file should be attempted");
        assertTrue(Files.exists(outDir.resolve("a.out")));
        assertTrue(Files.notExists(outDir.resolve("b.out")));
    }

    // --- safeOutput: the collision policy used by batch loops -------------

    @Test
    void safeOutputLeavesAFreePathUntouched() throws Exception {
        Path desired = tmp.resolve("free.pdf");
        assertEquals(desired, OperationRunner.safeOutput(desired, List.of(), OverwritePolicy.OVERWRITE));
    }

    @Test
    void safeOutputRenamesOffAnInputRegardlessOfPolicy() throws Exception {
        Path input = pdf("keep.pdf");
        Path renamed = OperationRunner.safeOutput(input, List.of(input), OverwritePolicy.OVERWRITE);
        assertNotEquals(input, renamed);
        assertEquals(tmp.resolve("keep (1).pdf"), renamed);
    }

    @Test
    void safeOutputHonoursPolicyForANonInputCollision() throws Exception {
        Path existing = tmp.resolve("there.pdf");
        Files.writeString(existing, "x");
        assertEquals(tmp.resolve("there (1).pdf"),
            OperationRunner.safeOutput(existing, List.of(), OverwritePolicy.RENAME));
        assertEquals(existing,
            OperationRunner.safeOutput(existing, List.of(), OverwritePolicy.OVERWRITE));
    }

    private static String stripExt(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(0, dot) : n;
    }
}
