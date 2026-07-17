package com.pdfconduit.core.operations;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.RedactOptions;
import com.pdfconduit.core.model.RedactRegion;
import com.pdfconduit.core.model.RedactResult;
import com.pdfconduit.core.util.OutputPaths;
import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Permanently redacts content from a PDF by <b>rasterising</b> every page that
 * carries redaction regions: the page is rendered to an image, the regions are
 * painted solid black, and the page is replaced by that image. Because the page
 * becomes a flat picture, there is no hidden text or vector data left under the
 * black boxes — unlike a black rectangle merely drawn on top, whose underlying
 * text would still be selectable and searchable.
 *
 * <p>Pages without any region are copied through untouched, so they keep their
 * text searchable; the cost of rasterisation (lost text layer, larger size) is
 * paid only on pages that actually need redacting.
 */
public final class PdfRedactor {

    private PdfRedactor() {}

    /** Render DPI used when {@link RedactOptions#dpi()} is not positive. */
    public static final int DEFAULT_DPI = 150;

    public static RedactResult execute(RedactOptions opts) throws PdfOperationException {
        try (PDDocument src = PdfLoader.load(opts.input());
             PDDocument out = new PDDocument()) {
            Counts counts = redact(src, out, opts.regions(), opts.dpi());
            OutputPaths.ensureParentDir(opts.output());
            out.save(opts.output().toFile());
            return new RedactResult(opts.output(), counts.pages(), counts.regions());
        } catch (IOException e) {
            throw new PdfOperationException("Redaction failed: " + e.getMessage(), e);
        }
    }

    /**
     * In-memory variant: permanently redact {@code regions} from {@code pdf} (rasterising
     * only the affected pages) and return the new PDF bytes.
     */
    public static byte[] executeBytes(byte[] pdf, List<RedactRegion> regions, int dpi)
            throws PdfOperationException {
        try (PDDocument src = PdfLoader.load(pdf);
             PDDocument out = new PDDocument()) {
            redact(src, out, regions, dpi);
            return PdfLoader.toBytes(out);
        } catch (IOException e) {
            throw new PdfOperationException("Redaction failed: " + e.getMessage(), e);
        }
    }

    /** Pages rasterised and regions painted, in one redaction pass. */
    private record Counts(int pages, int regions) {}

    /**
     * The shared algorithm: copy every page of {@code src} into {@code out}, rasterising
     * (and blacking out) only the pages that carry a region.
     */
    private static Counts redact(PDDocument src, PDDocument out, List<RedactRegion> regions, int dpiIn)
            throws IOException {
        int dpi = dpiIn > 0 ? dpiIn : DEFAULT_DPI;

        // Group non-empty regions by page; a zero-area rectangle is a no-op.
        Map<Integer, List<RedactRegion>> byPage = new LinkedHashMap<>();
        for (RedactRegion r : regions) {
            if (r.width() <= 0 || r.height() <= 0) continue;
            byPage.computeIfAbsent(r.pageIndex(), k -> new ArrayList<>()).add(r);
        }

        PDFRenderer renderer = new PDFRenderer(src);
        int total = src.getNumberOfPages();
        int redactedPages = 0, redactedRegions = 0;

        for (int i = 0; i < total; i++) {
            List<RedactRegion> pageRegions = byPage.get(i);
            if (pageRegions == null || pageRegions.isEmpty()) {
                out.importPage(src.getPage(i));   // unchanged: keep text searchable
                continue;
            }
            redactedRegions += rasterisePage(src, out, renderer, i, dpi, pageRegions);
            redactedPages++;
        }
        return new Counts(redactedPages, redactedRegions);
    }

    /** Renders page {@code i}, blacks out {@code regions}, and appends it to {@code out} as an image page. */
    private static int rasterisePage(PDDocument src, PDDocument out, PDFRenderer renderer,
                                     int i, int dpi, List<RedactRegion> regions) throws IOException {
        float[] size = displayedSize(src.getPage(i));      // points, rotation applied
        BufferedImage img = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
        double sx = img.getWidth()  / (double) size[0];    // pixels per point, X
        double sy = img.getHeight() / (double) size[1];    // pixels per point, Y

        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        for (RedactRegion r : regions) {
            int x = (int) Math.floor(r.x() * sx);
            int y = (int) Math.floor(r.y() * sy);
            int w = (int) Math.ceil(r.width()  * sx);
            int h = (int) Math.ceil(r.height() * sy);
            g.fillRect(x, y, w, h);
        }
        g.dispose();

        PDPage page = new PDPage(new PDRectangle(size[0], size[1]));
        out.addPage(page);
        PDImageXObject xobj = LosslessFactory.createFromImage(out, img);
        try (PDPageContentStream cs = new PDPageContentStream(out, page)) {
            cs.drawImage(xobj, 0, 0, size[0], size[1]);
        }
        return regions.size();
    }

    /** Rotation-aware {@code {widthPt, heightPt}} of a page as it is displayed. */
    private static float[] displayedSize(PDPage page) {
        PDRectangle box = page.getCropBox();
        float w = box.getWidth(), h = box.getHeight();
        int rot = ((page.getRotation() % 360) + 360) % 360;
        if (rot == 90 || rot == 270) { float t = w; w = h; h = t; }
        return new float[]{ w, h };
    }
}
