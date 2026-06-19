package org.example.app.gui.util;

import javafx.scene.image.Image;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the pages of a PDF into JavaFX {@link Image} thumbnails. Pure and
 * blocking — call it from a background thread (e.g. a {@link javafx.concurrent.Task}),
 * never on the FX thread. Converts via PNG bytes so no {@code javafx.swing}
 * dependency is needed.
 */
public final class PdfThumbnails {

    private PdfThumbnails() {}

    /** Progress callback fired after each page is rendered. */
    @FunctionalInterface
    public interface Progress {
        void update(int done, int total);
    }

    /** Renders every page of {@code pdf} at {@code dpi}; {@code progress} may be null. */
    public static List<Image> render(Path pdf, int dpi, Progress progress) throws Exception {
        List<Image> thumbs = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = doc.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                thumbs.add(toFxImage(renderer.renderImageWithDPI(i, dpi)));
                if (progress != null) progress.update(i + 1, pages);
            }
        }
        return thumbs;
    }

    private static Image toFxImage(BufferedImage img) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return new Image(new ByteArrayInputStream(bos.toByteArray()));
    }
}
