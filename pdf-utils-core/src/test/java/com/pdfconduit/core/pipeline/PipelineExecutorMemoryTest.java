package com.pdfconduit.core.pipeline;

import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.SplitMode;
import com.pdfconduit.core.service.NamedBytes;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PipelineExecutorMemoryTest {

    private static byte[] pdfBytes(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static int pageCount(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) { return doc.getNumberOfPages(); }
    }

    /** source(2 PDFs) → rotate → merge → terminal. Output must be one valid PDF with all pages. */
    @Test
    void sourceRotateMergeInMemory() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        PipelineNode rotate = new PipelineNode("r", NodeKind.ROTATE, 0, 0);
        rotate.angle = 90;
        PipelineNode merge = new PipelineNode("m", NodeKind.MERGE, 0, 0);
        m.nodes.add(src);
        m.nodes.add(rotate);
        m.nodes.add(merge);
        m.connections.add(new Connection("s", "r"));
        m.connections.add(new Connection("r", "m"));

        byte[] a = pdfBytes(2);
        byte[] b = pdfBytes(3);
        Map<String, List<NamedBytes>> out = PipelineExecutor.runInMemory(
            m, node -> node.id.equals("s") ? List.of(a, b) : List.of(), null);

        // Only the merge node is terminal.
        assertEquals(1, out.size());
        List<NamedBytes> merged = out.get("m");
        assertEquals(1, merged.size());
        byte[] pdf = merged.get(0).data();
        assertEquals(5, pageCount(pdf));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (int i = 0; i < 5; i++) assertEquals(90, doc.getPage(i).getRotation());
        }
        assertTrue(merged.get(0).filename().endsWith(".pdf"));
    }

    /** A terminal Extract-separate node returns one PDF per page as separate NamedBytes. */
    @Test
    void sourceExtractSeparateInMemory() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        PipelineNode extract = new PipelineNode("e", NodeKind.EXTRACT, 0, 0);
        extract.splitMode = SplitMode.SEPARATE;
        m.nodes.add(src);
        m.nodes.add(extract);
        m.connections.add(new Connection("s", "e"));

        byte[] doc = pdfBytes(3);
        Map<String, List<NamedBytes>> out = PipelineExecutor.runInMemory(
            m, node -> List.of(doc), null);

        List<NamedBytes> parts = out.get("e");
        assertEquals(3, parts.size());
        for (NamedBytes nb : parts) assertEquals(1, pageCount(nb.data()));
    }

    /** source(PDF) → watermark(image, bytes supplied via nodeImages) → terminal. Image is embedded. */
    @Test
    void sourceImageWatermarkInMemory() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        PipelineNode wm = new PipelineNode("w", NodeKind.WATERMARK, 0, 0);
        wm.wmText = "";
        wm.wmImage = "logo.png";   // name reference only; bytes come via nodeImages
        m.nodes.add(src);
        m.nodes.add(wm);
        m.connections.add(new Connection("s", "w"));

        byte[] pdf = pdfBytes(2);
        byte[] logo = pngLogo();
        Map<String, List<NamedBytes>> out = PipelineExecutor.runInMemory(
            m, node -> node.id.equals("s") ? List.of(pdf) : List.of(),
            Map.of("w", logo), null);

        List<NamedBytes> results = out.get("w");
        assertEquals(1, results.size());
        byte[] result = results.get(0).data();
        assertEquals(2, pageCount(result));
        try (PDDocument doc = Loader.loadPDF(result)) {
            for (PDPage page : doc.getPages()) {
                assertTrue(hasImage(page.getResources()), "each page should carry the watermark image");
            }
        }
    }

    /** An image watermark node with no bytes supplied for it fails clearly (not silently text). */
    @Test
    void imageWatermarkWithoutBytesFails() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        PipelineNode wm = new PipelineNode("w", NodeKind.WATERMARK, 0, 0);
        wm.wmText = "";
        wm.wmImage = "logo.png";
        m.nodes.add(src);
        m.nodes.add(wm);
        m.connections.add(new Connection("s", "w"));

        byte[] pdf = pdfBytes(1);
        assertThrows(PipelineException.class, () -> PipelineExecutor.runInMemory(
            m, node -> node.id.equals("s") ? List.of(pdf) : List.of(), Map.of(), null));
    }

    private static boolean hasImage(PDResources res) throws IOException {
        if (res == null) return false;
        for (COSName name : res.getXObjectNames()) {
            PDXObject xobj = res.getXObject(name);
            if (xobj instanceof PDImageXObject) return true;
        }
        return false;
    }

    private static byte[] pngLogo() throws IOException {
        BufferedImage img = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillOval(2, 2, 44, 44);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /**
     * A host guard is consulted for the client-supplied render DPI, and its runtime rejection
     * reaches the caller <em>unchanged</em> (not wrapped into a PipelineException) — that is what
     * lets the web layer map a pipeline rejection to the same status its single-operation endpoint
     * would return. The overloads without a guard (desktop/CLI) keep running unguarded, which every
     * other test in this class exercises.
     */
    @Test
    void guardSeesNodeRenderDpiAndItsRejectionPropagates() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        PipelineNode toImages = new PipelineNode("i", NodeKind.TO_IMAGES, 0, 0);
        toImages.imageDpi = 1200;
        m.nodes.add(src);
        m.nodes.add(toImages);
        m.connections.add(new Connection("s", "i"));

        int[] seen = {0};
        PipelineGuard guard = new PipelineGuard() {
            @Override public void checkRender(byte[] pdf, int dpi) {
                seen[0] = dpi;
                throw new IllegalStateException("dpi " + dpi + " too high");
            }
        };

        byte[] pdf = pdfBytes(1);
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> PipelineExecutor.runInMemory(
                m, node -> List.of(pdf), Map.of(), guard, null));
        assertEquals(1200, seen[0]);
        assertEquals("dpi 1200 too high", e.getMessage());
    }

    /** A guard's checked rejection is reported as a normal pipeline failure. */
    @Test
    void guardDocumentRejectionFailsTheRun() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        PipelineNode rotate = new PipelineNode("r", NodeKind.ROTATE, 0, 0);
        m.nodes.add(src);
        m.nodes.add(rotate);
        m.connections.add(new Connection("s", "r"));

        PipelineGuard guard = new PipelineGuard() {
            @Override public void checkDocument(byte[] pdf)
                    throws com.pdfconduit.core.exception.PdfOperationException {
                throw new com.pdfconduit.core.exception.PdfOperationException("too many pages");
            }
        };

        byte[] pdf = pdfBytes(2);
        PipelineException e = assertThrows(PipelineException.class,
            () -> PipelineExecutor.runInMemory(m, node -> List.of(pdf), Map.of(), guard, null));
        assertTrue(e.getMessage().contains("too many pages"));
    }

    /**
     * ARRANGE amplifies: repeats in the order expression duplicate pages, so a one-page input can
     * be expanded without limit. The guard must see the EXPANDED count (5 here, not the input's 1)
     * and must see it <em>before</em> the document is built — so the check is what stops the work,
     * not a post-hoc complaint about something already in memory.
     */
    @Test
    void arrangeGuardsTheExpandedPageCount() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        PipelineNode arrange = new PipelineNode("a", NodeKind.ARRANGE, 0, 0);
        arrange.order = "1,1,1,1,1";
        m.nodes.add(src);
        m.nodes.add(arrange);
        m.connections.add(new Connection("s", "a"));

        int[] seen = {-1};
        PipelineGuard guard = new PipelineGuard() {
            @Override public void checkPageCount(int pages)
                    throws com.pdfconduit.core.exception.PdfOperationException {
                seen[0] = pages;
                throw new com.pdfconduit.core.exception.PdfOperationException("too many pages");
            }
        };

        byte[] pdf = pdfBytes(1);
        PipelineException e = assertThrows(PipelineException.class,
            () -> PipelineExecutor.runInMemory(m, node -> List.of(pdf), Map.of(), guard, null));
        assertEquals(5, seen[0], "the guard must see the expanded order length, not the input count");
        assertTrue(e.getMessage().contains("too many pages"));
    }

    /** Within the ceiling an arrange is still a plain reorder — the guard bounds abuse, not use. */
    @Test
    void arrangeWithinTheCeilingStillRuns() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        PipelineNode arrange = new PipelineNode("a", NodeKind.ARRANGE, 0, 0);
        arrange.order = "2,1";
        m.nodes.add(src);
        m.nodes.add(arrange);
        m.connections.add(new Connection("s", "a"));

        PipelineGuard guard = new PipelineGuard() {
            @Override public void checkPageCount(int pages)
                    throws com.pdfconduit.core.exception.PdfOperationException {
                if (pages > 10) throw new com.pdfconduit.core.exception.PdfOperationException("nope");
            }
        };

        byte[] pdf = pdfBytes(2);
        Map<String, List<NamedBytes>> out =
            PipelineExecutor.runInMemory(m, node -> List.of(pdf), Map.of(), guard, null);
        assertEquals(2, pageCount(out.get("a").get(0).data()));
    }

    /**
     * A bad page expression must leave the executor as itself, not wrapped as an operation failure:
     * hosts map {@code InvalidPageRangeException} to a client error (the web layer: 400
     * {@code invalid_page_range}), and a pipeline must not downgrade that to a server-side 422.
     */
    @Test
    void badPageExpressionPropagatesUnwrapped() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        PipelineNode rotate = new PipelineNode("r", NodeKind.ROTATE, 0, 0);
        rotate.pages = "abc";
        rotate.angle = 90;
        m.nodes.add(src);
        m.nodes.add(rotate);
        m.connections.add(new Connection("s", "r"));

        byte[] pdf = pdfBytes(2);
        assertThrows(com.pdfconduit.core.exception.InvalidPageRangeException.class,
            () -> PipelineExecutor.runInMemory(m, node -> List.of(pdf), Map.of(), null, null));
    }

    @Test
    void rejectsUnsupportedSourceBytes() {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 0, 0);
        PipelineNode rotate = new PipelineNode("r", NodeKind.ROTATE, 0, 0);
        m.nodes.add(src);
        m.nodes.add(rotate);
        m.connections.add(new Connection("s", "r"));

        assertThrows(PipelineException.class, () -> PipelineExecutor.runInMemory(
            m, node -> List.of("not a pdf".getBytes()), null));
    }
}
