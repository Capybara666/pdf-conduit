package com.pdfconduit.app.cli;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import com.pdfconduit.core.model.PdfMetadata;
import com.pdfconduit.core.operations.PdfMetadataEditor;
import com.pdfconduit.core.pipeline.Connection;
import com.pdfconduit.core.pipeline.NodeKind;
import com.pdfconduit.core.pipeline.PipelineModel;
import com.pdfconduit.core.pipeline.PipelineNode;
import com.pdfconduit.core.pipeline.PipelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliIntegrationTest {

    @TempDir Path tmp;

    @Test
    void mergeCommandProducesOutput() throws Exception {
        Path a = createPdf(2);
        Path b = createPdf(3);
        Path out = tmp.resolve("merged.pdf");

        int exit = new CommandLine(new RootCommand())
            .execute("merge", a.toString(), b.toString(), "-o", out.toString());

        assertEquals(0, exit);
        assertTrue(out.toFile().exists());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(5, doc.getNumberOfPages());
        }
    }

    @Test
    void splitCommandExtractsPages() throws Exception {
        Path src = createPdf(5);
        Path out = tmp.resolve("split.pdf");

        int exit = new CommandLine(new RootCommand())
            .execute("split", src.toString(), "--pages", "1-3", "-o", out.toString());

        assertEquals(0, exit);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(3, doc.getNumberOfPages());
        }
    }

    @Test
    void splitSeparateWritesOneFilePerPage() throws Exception {
        Path src = createPdf(4);
        Path dir = tmp.resolve("burst");

        int exit = new CommandLine(new RootCommand())
            .execute("split", src.toString(), "--separate", "-o", dir.toString());

        assertEquals(0, exit);
        try (var stream = java.nio.file.Files.list(dir)) {
            assertEquals(4, stream.filter(p -> p.toString().endsWith(".pdf")).count());
        }
    }

    @Test
    void rotateCommandRotatesPages() throws Exception {
        Path src = createPdf(3);
        Path out = tmp.resolve("rotated.pdf");

        int exit = new CommandLine(new RootCommand())
            .execute("rotate", src.toString(), "--angle", "90", "--pages", "1", "-o", out.toString());

        assertEquals(0, exit);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(90, doc.getPage(0).getRotation());
        }
    }

    @Test
    void arrangeCommandReordersPages() throws Exception {
        Path src = createPdf(3);
        Path out = tmp.resolve("arranged.pdf");

        int exit = new CommandLine(new RootCommand())
            .execute("arrange", src.toString(), "--order", "3,1", "-o", out.toString());

        assertEquals(0, exit);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(2, doc.getNumberOfPages());   // page 2 dropped, 3 then 1 kept
        }
    }

    @Test
    void arrangeInvalidOrderReturnsExitCode1() throws Exception {
        Path src = createPdf(2);
        int exit = new CommandLine(new RootCommand())
            .execute("arrange", src.toString(), "--order", "9",
                     "-o", tmp.resolve("out.pdf").toString());
        assertEquals(1, exit);
    }

    @Test
    void invalidAngleReturnsExitCode1() throws Exception {
        Path src = createPdf(1);
        int exit = new CommandLine(new RootCommand())
            .execute("rotate", src.toString(), "--angle", "45",
                     "-o", tmp.resolve("out.pdf").toString());
        assertEquals(1, exit);
    }

    @Test
    void sizeConverterParsesFormats() throws Exception {
        SizeConverter converter = new SizeConverter();
        assertEquals(500L * 1024,               converter.convert("500KB"));
        assertEquals(5L * 1024 * 1024,          converter.convert("5MB"));
        assertEquals((long)(1.5 * 1024 * 1024), converter.convert("1.5MB"));
    }

    @Test
    void deriveOutputAlwaysProducesPdf() {
        // Default output is a PDF regardless of the first input's extension.
        assertTrue(MergeCommand.deriveOutput(Path.of("/x/a.png"),  "_merged").toString().endsWith("a_merged.pdf"));
        assertTrue(MergeCommand.deriveOutput(Path.of("/x/a.docx"), "_merged").toString().endsWith("a_merged.pdf"));
        assertTrue(MergeCommand.deriveOutput(Path.of("/x/a.pdf"),  "_merged").toString().endsWith("a_merged.pdf"));
    }

    @Test
    void toPdfCombinesImageIntoPdf() throws Exception {
        Path png = tmp.resolve("pic.png");
        javax.imageio.ImageIO.write(
            new java.awt.image.BufferedImage(60, 40, java.awt.image.BufferedImage.TYPE_INT_RGB),
            "png", png.toFile());
        Path out = tmp.resolve("album.pdf");

        int exit = new CommandLine(new RootCommand())
            .execute("to-pdf", png.toString(), "--page-size", "A4", "-o", out.toString());

        assertEquals(0, exit);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    void protectAndUnlockRoundTripViaCli() throws Exception {
        Path src = createPdf(2);
        Path locked = tmp.resolve("locked.pdf");
        Path unlocked = tmp.resolve("unlocked.pdf");

        int e1 = new CommandLine(new RootCommand())
            .execute("protect", src.toString(), "--password", "pw", "-o", locked.toString());
        assertEquals(0, e1);
        assertThrows(IOException.class, () -> {
            try (PDDocument d = Loader.loadPDF(locked.toFile())) { d.getNumberOfPages(); }
        });

        int e2 = new CommandLine(new RootCommand())
            .execute("unlock", locked.toString(), "--password", "pw", "-o", unlocked.toString());
        assertEquals(0, e2);
        try (PDDocument d = Loader.loadPDF(unlocked.toFile())) {
            assertEquals(2, d.getNumberOfPages());
        }
    }

    @Test
    void compressingAProtectedPdfReturnsExitCode2() throws Exception {
        Path src = createPdf(2);
        Path locked = tmp.resolve("locked-c.pdf");
        new CommandLine(new RootCommand())
            .execute("protect", src.toString(), "--password", "pw", "-o", locked.toString());

        int exit = new CommandLine(new RootCommand())
            .execute("compress", locked.toString(), "--target-size", "100KB",
                     "-o", tmp.resolve("out.pdf").toString());
        assertEquals(2, exit);   // operation failed (protected), not a crash
    }

    @Test
    void unlockWithWrongPasswordReturnsExitCode2() throws Exception {
        Path src = createPdf(1);
        Path locked = tmp.resolve("locked.pdf");
        new CommandLine(new RootCommand())
            .execute("protect", src.toString(), "--password", "pw", "-o", locked.toString());

        int exit = new CommandLine(new RootCommand())
            .execute("unlock", locked.toString(), "--password", "nope",
                     "-o", tmp.resolve("x.pdf").toString());
        assertEquals(2, exit);
    }

    @Test
    void metadataShowExitsZero() throws Exception {
        int exit = new CommandLine(new RootCommand())
            .execute("metadata", createPdf(1).toString(), "--show");
        assertEquals(0, exit);
    }

    @Test
    void metadataSetThenStripViaCli() throws Exception {
        Path src = createPdf(1);
        Path set = tmp.resolve("set.pdf");
        int e1 = new CommandLine(new RootCommand())
            .execute("metadata", src.toString(), "--title", "Hello", "--author", "Me",
                     "-o", set.toString());
        assertEquals(0, e1);
        PdfMetadata md = PdfMetadataEditor.read(set);
        assertEquals("Hello", md.title());
        assertEquals("Me", md.author());

        Path stripped = tmp.resolve("stripped.pdf");
        int e2 = new CommandLine(new RootCommand())
            .execute("metadata", set.toString(), "--strip", "-o", stripped.toString());
        assertEquals(0, e2);
        PdfMetadata after = PdfMetadataEditor.read(stripped);
        assertTrue(after.title() == null || after.title().isBlank());
    }

    @Test
    void watermarkTextViaCli() throws Exception {
        Path src = createPdf(2);
        Path out = tmp.resolve("wm.pdf");
        int exit = new CommandLine(new RootCommand())
            .execute("watermark", src.toString(), "--text", "DRAFT", "-o", out.toString());
        assertEquals(0, exit);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals(2, d.getNumberOfPages());
        }
    }

    @Test
    void watermarkImageViaCli() throws Exception {
        Path src = createPdf(1);
        Path logo = tmp.resolve("logo.png");
        javax.imageio.ImageIO.write(
            new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB),
            "png", logo.toFile());
        Path out = tmp.resolve("wmi.pdf");
        int exit = new CommandLine(new RootCommand())
            .execute("watermark", src.toString(), "--image", logo.toString(), "-o", out.toString());
        assertEquals(0, exit);
        assertTrue(out.toFile().exists());
    }

    @Test
    void runsASavedPipeline() throws Exception {
        Path src = createPdf(2);
        Path outFile = tmp.resolve("piped.pdf");
        PipelineModel m = new PipelineModel();
        PipelineNode s = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        s.files.add(src);
        PipelineNode r = new PipelineNode("r", NodeKind.ROTATE, 0, 0);
        r.angle = 90;
        r.outputDestination = outFile.toString();
        m.nodes.add(s);
        m.nodes.add(r);
        m.connections.add(new Connection("s", "r"));
        Path json = tmp.resolve("pipe.json");
        PipelineStore.save(m, json);

        int exit = new CommandLine(new RootCommand()).execute("pipeline", json.toString());

        assertEquals(0, exit);
        assertTrue(outFile.toFile().exists());
        try (PDDocument d = Loader.loadPDF(outFile.toFile())) {
            assertEquals(2, d.getNumberOfPages());
        }
    }

    @Test
    void pipelineWithMissingFileReturnsExitCode1() {
        int exit = new CommandLine(new RootCommand())
            .execute("pipeline", tmp.resolve("nope.json").toString());
        assertEquals(1, exit);
    }

    @Test
    void helpExitsZero() {
        int exit = new CommandLine(new RootCommand()).execute("--help");
        assertEquals(0, exit);
    }

    private Path createPdf(int pages) throws IOException {
        Path path = tmp.resolve("test-" + pages + "-" + System.nanoTime() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(path.toFile());
        }
        return path;
    }
}
