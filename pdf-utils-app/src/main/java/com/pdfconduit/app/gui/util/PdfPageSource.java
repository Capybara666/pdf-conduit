package com.pdfconduit.app.gui.util;

import javafx.scene.image.Image;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import com.pdfconduit.core.util.PdfLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

/**
 * A single open PDF kept ready for on-demand, page-by-page rendering — the engine
 * behind the {@link com.pdfconduit.app.gui.component.PdfViewer in-app viewer}.
 *
 * <p>Unlike {@link PdfThumbnails} (which renders every page up front), this holds
 * the {@link PDDocument} open and renders one page at a time at whatever DPI the
 * current zoom calls for, so large documents stay cheap to browse.
 *
 * <p><b>Threading:</b> PDFBox's {@link PDFRenderer}/{@link PDDocument} are not
 * thread-safe. This class is blocking and must be driven from a single background
 * thread (the viewer serialises every call onto one executor); never touch it from
 * the FX thread. Page sizes are read once at construction so the FX thread can do
 * fit-to-width/page maths without reaching into PDFBox.
 */
public final class PdfPageSource implements AutoCloseable {

    private final PDDocument doc;
    private final PDFRenderer renderer;
    private final float[][] sizesPt;

    public PdfPageSource(Path pdf) throws Exception {
        this.doc = PdfLoader.load(pdf);   // clear messages for protected / damaged files
        this.renderer = new PDFRenderer(doc);
        int n = doc.getNumberOfPages();
        this.sizesPt = new float[n][];
        for (int i = 0; i < n; i++) {
            PDPage page = doc.getPage(i);
            PDRectangle box = page.getCropBox();
            float w = box.getWidth(), h = box.getHeight();
            int rot = ((page.getRotation() % 360) + 360) % 360;
            if (rot == 90 || rot == 270) { float t = w; w = h; h = t; }
            sizesPt[i] = new float[]{ w, h };
        }
    }

    public int pageCount() { return sizesPt.length; }

    /** Rotation-aware {@code {widthPt, heightPt}} of every page, for fit maths on the FX thread. */
    public float[][] sizesPt() { return sizesPt; }

    /** Renders one page at {@code dpi} into a JavaFX image (via PNG bytes, so no Swing dependency). */
    public Image render(int pageIndex, float dpi) throws Exception {
        BufferedImage img = renderer.renderImageWithDPI(pageIndex, dpi);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return new Image(new ByteArrayInputStream(bos.toByteArray()));
    }

    @Override
    public void close() throws Exception { doc.close(); }
}
