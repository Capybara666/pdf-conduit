package org.example.app.pipeline;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PipelineExecutorTest {

    @TempDir Path tmp;

    private Path pdf(String name, int pages) throws IOException {
        Path p = tmp.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(p.toFile());
        }
        return p;
    }

    private int pageCount(Path p) throws IOException {
        try (PDDocument doc = Loader.loadPDF(p.toFile())) {
            return doc.getNumberOfPages();
        }
    }

    @Test
    void sourceMergeToSingleFile() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(pdf("a.pdf", 2));
        src.files.add(pdf("b.pdf", 3));
        PipelineNode merge = new PipelineNode("m", NodeKind.MERGE, 0, 0);
        Path out = tmp.resolve("merged.pdf");
        merge.outputDestination = out.toString();
        m.nodes.add(src);
        m.nodes.add(merge);
        m.connections.add(new Connection("s", "m"));

        PipelineExecutor.run(m, null);

        assertTrue(Files.exists(out));
        assertEquals(5, pageCount(out));
    }

    @Test
    void mapToFolderProducesOneFilePerInput() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(pdf("a.pdf", 2));
        src.files.add(pdf("b.pdf", 1));
        PipelineNode rotate = new PipelineNode("r", NodeKind.ROTATE, 0, 0);
        rotate.angle = 90;
        Path outDir = tmp.resolve("rotated-out"); // no .pdf extension -> folder
        rotate.outputDestination = outDir.toString();
        m.nodes.add(src);
        m.nodes.add(rotate);
        m.connections.add(new Connection("s", "r"));

        PipelineExecutor.Result result = PipelineExecutor.run(m, null);

        assertTrue(Files.isDirectory(outDir));
        try (var stream = Files.list(outDir)) {
            assertEquals(2, stream.filter(p -> p.toString().endsWith(".pdf")).count());
        }
        assertEquals(2, result.savedByNode().get("r").size());
    }

    @Test
    void toPdfProducesOneFilePerInputNotAMerge() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(pdf("a.pdf", 2));
        src.files.add(pdf("b.pdf", 3));
        PipelineNode toPdf = new PipelineNode("p", NodeKind.IMAGES_TO_PDF, 0, 0);
        Path outDir = tmp.resolve("topdf-out"); // folder
        toPdf.outputDestination = outDir.toString();
        m.nodes.add(src);
        m.nodes.add(toPdf);
        m.connections.add(new Connection("s", "p"));

        PipelineExecutor.Result result = PipelineExecutor.run(m, null);

        // Two inputs -> two outputs (no merge), each keeping its own page count.
        assertEquals(2, result.savedByNode().get("p").size());
        try (var stream = Files.list(outDir)) {
            assertEquals(2, stream.filter(p -> p.toString().endsWith(".pdf")).count());
        }
        for (Path out : result.savedByNode().get("p")) {
            int pages = pageCount(out);
            assertTrue(pages == 2 || pages == 3, "expected per-input page counts, got " + pages);
        }
    }

    @Test
    void chainMapThenReduce() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(pdf("a.pdf", 2));
        src.files.add(pdf("b.pdf", 3));
        PipelineNode compress = new PipelineNode("c", NodeKind.COMPRESS, 0, 0);
        compress.targetBytes = 50L * 1024 * 1024;          // generous; just passes through
        PipelineNode merge = new PipelineNode("mg", NodeKind.MERGE, 0, 0);
        Path out = tmp.resolve("final.pdf");
        merge.outputDestination = out.toString();
        m.nodes.add(src);
        m.nodes.add(compress);
        m.nodes.add(merge);
        m.connections.add(new Connection("s", "c"));
        m.connections.add(new Connection("c", "mg"));

        PipelineExecutor.run(m, null);

        assertTrue(Files.exists(out));
        assertEquals(5, pageCount(out));
    }

    @Test
    void invalidPipelineThrows() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(pdf("a.pdf", 1));
        m.nodes.add(src);
        PipelineNode compress = new PipelineNode("c", NodeKind.COMPRESS, 0, 0); // no destination
        m.nodes.add(compress);
        m.connections.add(new Connection("s", "c"));

        assertThrows(PipelineException.class, () -> PipelineExecutor.run(m, null));
    }
}
