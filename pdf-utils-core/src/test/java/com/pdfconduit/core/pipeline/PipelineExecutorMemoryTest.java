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
