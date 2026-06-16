package org.example.app.cli;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
