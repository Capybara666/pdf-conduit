package org.example.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.CompressOptions;
import org.example.core.model.CompressResult;
import org.example.core.util.OutputPaths;
import org.example.core.util.SizeEstimator;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;

public class PdfCompressor {

    private static final int[]   DPI_LEVELS     = {300, 200, 150, 96};
    private static final float[] QUALITY_LEVELS = {0.9f, 0.7f, 0.5f, 0.3f};

    public static CompressResult execute(CompressOptions opts) throws PdfOperationException {
        try {
            long originalSize = opts.input().toFile().length();
            OutputPaths.ensureParentDir(opts.output());

            for (int dpiIdx = 0; dpiIdx < DPI_LEVELS.length; dpiIdx++) {
                for (float quality : QUALITY_LEVELS) {
                    try (PDDocument compressed = Loader.loadPDF(opts.input().toFile())) {
                        downsampleImages(compressed, DPI_LEVELS[dpiIdx], quality);
                        long estimated = SizeEstimator.estimateBytes(compressed);
                        if (estimated <= opts.targetSizeBytes()) {
                            compressed.save(opts.output().toFile());
                            return new CompressResult(opts.output(), originalSize,
                                opts.output().toFile().length(), true);
                        }
                    }
                }
            }

            // Target unreachable — save most-compressed version, but never larger than original
            try (PDDocument compressed = Loader.loadPDF(opts.input().toFile())) {
                downsampleImages(compressed, DPI_LEVELS[DPI_LEVELS.length - 1],
                                 QUALITY_LEVELS[QUALITY_LEVELS.length - 1]);
                long estimatedBest = SizeEstimator.estimateBytes(compressed);
                if (estimatedBest < originalSize) {
                    compressed.save(opts.output().toFile());
                } else {
                    java.nio.file.Files.copy(opts.input(), opts.output(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return new CompressResult(opts.output(), originalSize,
                opts.output().toFile().length(), false);

        } catch (IOException e) {
            throw new PdfOperationException("Compression failed: " + e.getMessage(), e);
        }
    }

    private static void downsampleImages(PDDocument doc, int targetDpi, float quality)
            throws IOException {
        for (PDPage page : doc.getPages()) {
            PDResources resources = page.getResources();
            if (resources == null) continue;
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xobj = resources.getXObject(name);
                if (!(xobj instanceof PDImageXObject image)) continue;

                float scale = Math.min(1f, (float) targetDpi / 150f);
                int newW = Math.max(1, (int)(image.getWidth()  * scale));
                int newH = Math.max(1, (int)(image.getHeight() * scale));
                if (newW >= image.getWidth() && newH >= image.getHeight()) continue;

                BufferedImage bi = image.getImage();
                BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                   RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(bi, 0, 0, newW, newH, null);
                g.dispose();

                resources.put(name, JPEGFactory.createFromImage(doc, scaled, quality));
            }
        }
    }
}
