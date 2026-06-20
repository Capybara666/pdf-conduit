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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class PdfCompressor {

    /** One rung of the compression ladder: scale the images, then JPEG-encode at {@code quality}. */
    private record Step(float scale, float quality) {}

    /**
     * Tried in order, gentlest first. The full-resolution rungs (scale 1.0) come
     * before any downscaling, so a target reachable by re-encoding alone never
     * sacrifices resolution; only once those are exhausted do we shrink images.
     */
    private static final List<Step> STEPS = List.of(
        new Step(1.00f, 0.85f),
        new Step(1.00f, 0.60f),
        new Step(1.00f, 0.40f),
        new Step(0.75f, 0.60f),
        new Step(0.75f, 0.40f),
        new Step(0.50f, 0.60f),
        new Step(0.50f, 0.40f),
        new Step(0.33f, 0.40f)
    );

    private PdfCompressor() {}

    public static CompressResult execute(CompressOptions opts) throws PdfOperationException {
        try {
            long originalSize = opts.input().toFile().length();
            OutputPaths.ensureParentDir(opts.output());

            for (Step step : STEPS) {
                try (PDDocument compressed = Loader.loadPDF(opts.input().toFile())) {
                    recompressImages(compressed, step.scale(), step.quality());
                    if (SizeEstimator.estimateBytes(compressed) <= opts.targetSizeBytes()) {
                        compressed.save(opts.output().toFile());
                        return new CompressResult(opts.output(), originalSize,
                            opts.output().toFile().length(), true);
                    }
                }
            }

            // Target unreachable — save the most-compressed version, but never larger than original.
            try (PDDocument compressed = Loader.loadPDF(opts.input().toFile())) {
                Step strongest = STEPS.get(STEPS.size() - 1);
                recompressImages(compressed, strongest.scale(), strongest.quality());
                if (SizeEstimator.estimateBytes(compressed) < originalSize) {
                    compressed.save(opts.output().toFile());
                } else {
                    Files.copy(opts.input(), opts.output(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return new CompressResult(opts.output(), originalSize,
                opts.output().toFile().length(), false);

        } catch (IOException e) {
            throw new PdfOperationException("Compression failed: " + e.getMessage(), e);
        }
    }

    /**
     * Re-encodes every image on every page as JPEG at {@code quality}, first scaling
     * it by {@code scale} (1.0 keeps the pixel dimensions but still re-encodes —
     * which is what shrinks losslessly-stored images). Images are never upscaled.
     */
    private static void recompressImages(PDDocument doc, float scale, float quality)
            throws IOException {
        for (PDPage page : doc.getPages()) {
            PDResources resources = page.getResources();
            if (resources == null) continue;
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xobj = resources.getXObject(name);
                if (!(xobj instanceof PDImageXObject image)) continue;

                int newW = clampDimension(image.getWidth(), scale);
                int newH = clampDimension(image.getHeight(), scale);

                BufferedImage rgb = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                   RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(image.getImage(), 0, 0, newW, newH, null);
                g.dispose();

                resources.put(name, JPEGFactory.createFromImage(doc, rgb, quality));
            }
        }
    }

    /** Scaled dimension, at least 1px and never larger than the original. */
    private static int clampDimension(int original, float scale) {
        return Math.max(1, Math.min(original, Math.round(original * scale)));
    }
}
