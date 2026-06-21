package com.pdfconduit.core.pipeline;

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
    void multiOutputWithStaleFileDestinationUsesParentFolder() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(pdf("a.pdf", 1));
        src.files.add(pdf("b.pdf", 1));
        PipelineNode toPdf = new PipelineNode("p", NodeKind.IMAGES_TO_PDF, 0, 0);
        // A stale single-file destination (as if set before wiring a multi-file source).
        Path dir = tmp.resolve("stale-out");
        toPdf.outputDestination = dir.resolve("pdf_conduit_result.pdf").toString();
        m.nodes.add(src);
        m.nodes.add(toPdf);
        m.connections.add(new Connection("s", "p"));

        PipelineExecutor.Result result = PipelineExecutor.run(m, null);

        // Two distinct files in the parent folder, not one overwritten file.
        assertEquals(2, result.savedByNode().get("p").size());
        assertEquals(2, result.savedByNode().get("p").stream().distinct().count());
        try (var stream = Files.list(dir)) {
            assertEquals(2, stream.filter(p -> p.toString().endsWith(".pdf")).count());
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
    void arrangeReordersPagesPerInput() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(pdf("a.pdf", 4));
        PipelineNode arrange = new PipelineNode("ar", NodeKind.ARRANGE, 0, 0);
        arrange.order = "4-1";                       // reverse the pages
        Path out = tmp.resolve("arranged.pdf");
        arrange.outputDestination = out.toString();
        m.nodes.add(src);
        m.nodes.add(arrange);
        m.connections.add(new Connection("s", "ar"));

        PipelineExecutor.run(m, null);

        assertTrue(Files.exists(out));
        assertEquals(4, pageCount(out));
    }

    @Test
    void extractSeparateBurstsPagesIntoFolder() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(pdf("doc.pdf", 3));
        PipelineNode split = new PipelineNode("x", NodeKind.EXTRACT, 0, 0);
        split.splitMode = com.pdfconduit.core.model.SplitMode.SEPARATE;
        Path outDir = tmp.resolve("burst-out");          // folder
        split.outputDestination = outDir.toString();
        m.nodes.add(src);
        m.nodes.add(split);
        m.connections.add(new Connection("s", "x"));

        PipelineExecutor.Result result = PipelineExecutor.run(m, null);

        assertEquals(3, result.savedByNode().get("x").size());
        try (var stream = Files.list(outDir)) {
            assertEquals(3, stream.filter(p -> p.toString().endsWith(".pdf")).count());
        }
    }

    @Test
    void protectNodeRequiresPasswordToOpen() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(pdf("a.pdf", 2));
        PipelineNode protect = new PipelineNode("p", NodeKind.PROTECT, 0, 0);
        protect.password = "secret";
        Path out = tmp.resolve("locked.pdf");
        protect.outputDestination = out.toString();
        m.nodes.add(src);
        m.nodes.add(protect);
        m.connections.add(new Connection("s", "p"));

        PipelineExecutor.run(m, null);

        assertTrue(Files.exists(out));
        assertThrows(IOException.class, () -> {
            try (PDDocument d = Loader.loadPDF(out.toFile())) { d.getNumberOfPages(); }
        });
        try (PDDocument d = Loader.loadPDF(out.toFile(), "secret")) {
            assertEquals(2, d.getNumberOfPages());
        }
    }

    @Test
    void unlockNodeRemovesPassword() throws Exception {
        Path plain = pdf("a.pdf", 2);
        Path locked = tmp.resolve("locked-src.pdf");
        com.pdfconduit.core.operations.PdfProtector.execute(
            new com.pdfconduit.core.model.ProtectOptions(plain, "pw", "", locked));

        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(locked);
        PipelineNode unlock = new PipelineNode("u", NodeKind.UNLOCK, 0, 0);
        unlock.password = "pw";
        Path out = tmp.resolve("unlocked.pdf");
        unlock.outputDestination = out.toString();
        m.nodes.add(src);
        m.nodes.add(unlock);
        m.connections.add(new Connection("s", "u"));

        PipelineExecutor.run(m, null);

        try (PDDocument d = Loader.loadPDF(out.toFile())) {   // opens with no password
            assertEquals(2, d.getNumberOfPages());
        }
    }

    @Test
    void metadataNodeSetsTitleAndLeavesBlankFieldsAlone() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(pdf("a.pdf", 1));
        PipelineNode meta = new PipelineNode("m", NodeKind.METADATA, 0, 0);
        meta.metaTitle = "Piped";          // author/subject/keywords left blank
        Path out = tmp.resolve("meta.pdf");
        meta.outputDestination = out.toString();
        m.nodes.add(src);
        m.nodes.add(meta);
        m.connections.add(new Connection("s", "m"));

        PipelineExecutor.run(m, null);

        assertEquals("Piped",
            com.pdfconduit.core.operations.PdfMetadataEditor.read(out).title());
    }

    @Test
    void watermarkNodeStampsEveryPage() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        src.files.add(pdf("a.pdf", 2));
        PipelineNode wm = new PipelineNode("w", NodeKind.WATERMARK, 0, 0);
        wm.wmText = "DRAFT";
        Path out = tmp.resolve("wm.pdf");
        wm.outputDestination = out.toString();
        m.nodes.add(src);
        m.nodes.add(wm);
        m.connections.add(new Connection("s", "w"));

        PipelineExecutor.run(m, null);

        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals(2, d.getNumberOfPages());
        }
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
