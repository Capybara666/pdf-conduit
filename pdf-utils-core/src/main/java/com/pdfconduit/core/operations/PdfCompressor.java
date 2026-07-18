package com.pdfconduit.core.operations;

import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.CompressBytesResult;
import com.pdfconduit.core.model.CompressOptions;
import com.pdfconduit.core.model.CompressResult;
import com.pdfconduit.core.util.OutputPaths;
import com.pdfconduit.core.util.PdfLoader;
import com.pdfconduit.core.util.SizeEstimator;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * A hook invoked once, during the lossless pass, with the freshly-parsed document's page count,
     * so a caller (the web backend's PDF-bomb ceiling) can enforce its page-count guard without
     * opening the PDF a second time just to count pages. May throw to abort compression.
     */
    @FunctionalInterface
    public interface PageCountGuard {
        void check(int pageCount) throws PdfOperationException;
    }

    private PdfCompressor() {}

    public static CompressResult execute(CompressOptions opts) throws PdfOperationException {
        try {
            long originalSize = opts.input().toFile().length();
            OutputPaths.ensureParentDir(opts.output());

            // 1) Lossless pass: re-save with object-stream compression (PDFBox's
            //    default), which also drops orphaned objects. This is the only
            //    lever for text/vector PDFs and never degrades quality.
            boolean hasImages;
            try (PDDocument doc = PdfLoader.load(opts.input())) {
                hasImages = hasImages(doc);
                doc.save(opts.output().toFile());
            }
            long losslessBytes = opts.output().toFile().length();
            if (losslessBytes <= opts.targetSizeBytes()) {
                return new CompressResult(opts.output(), originalSize, losslessBytes, true);
            }

            // 2) Nothing to downsample: the lossless copy is the best we can do
            //    (guard against the rare case where it ended up larger).
            if (!hasImages) {
                if (losslessBytes > originalSize) {
                    Files.copy(opts.input(), opts.output(), StandardCopyOption.REPLACE_EXISTING);
                }
                return new CompressResult(opts.output(), originalSize,
                    opts.output().toFile().length(), false);
            }

            // 3) Lossy image ladder, gentlest first; stop as soon as the target is met.
            //    Each rung reloads a pristine copy of the PDF (so orphaned objects never
            //    accumulate and the output is byte-for-byte what a per-rung reload always
            //    produced), but the expensive part — decoding each source image's raster —
            //    is memoised across rungs in decodeCache, so a source image is rasterised
            //    once instead of once per rung. Only the redundant decode is avoided.
            DecodeCache decodeCache = new DecodeCache();
            for (Step step : STEPS) {
                try (PDDocument compressed = PdfLoader.load(opts.input())) {
                    recompressImages(compressed, step.scale(), step.quality(), decodeCache);
                    if (SizeEstimator.estimateBytes(compressed) <= opts.targetSizeBytes()) {
                        compressed.save(opts.output().toFile());
                        return new CompressResult(opts.output(), originalSize,
                            opts.output().toFile().length(), true);
                    }
                }
            }

            // 4) Target unreachable — save the most-compressed version, but never larger than original.
            try (PDDocument compressed = PdfLoader.load(opts.input())) {
                Step strongest = STEPS.get(STEPS.size() - 1);
                recompressImages(compressed, strongest.scale(), strongest.quality(), decodeCache);
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
     * In-memory variant: compress {@code input} toward {@code targetSizeBytes} using the same
     * lossless-then-image-ladder strategy as {@link #execute}, but reading and writing bytes.
     * The result is never larger than the input.
     */
    public static CompressBytesResult compressBytes(byte[] input, long targetSizeBytes)
            throws PdfOperationException {
        return compressBytes(input, targetSizeBytes, null);
    }

    /**
     * As {@link #compressBytes(byte[], long)}, but the lossless pass — which already parses
     * {@code input} — hands its page count to {@code pageCountGuard} (may be {@code null}) before
     * doing any work, letting a caller enforce a page-count ceiling without a separate parse. The
     * compression algorithm and its "never larger than the input" guarantee are unchanged.
     */
    public static CompressBytesResult compressBytes(byte[] input, long targetSizeBytes,
                                                    PageCountGuard pageCountGuard)
            throws PdfOperationException {
        long originalSize = input.length;
        try {
            // 1) Lossless pass: re-save with object-stream compression.
            byte[] lossless;
            boolean hasImages;
            try (PDDocument doc = PdfLoader.load(input)) {
                if (pageCountGuard != null) pageCountGuard.check(doc.getNumberOfPages());
                hasImages = hasImages(doc);
                lossless = PdfLoader.toBytes(doc);
            }
            if (lossless.length <= targetSizeBytes) {
                return new CompressBytesResult(lossless, originalSize, lossless.length, true);
            }

            // 2) Nothing to downsample: the lossless copy is the best we can do.
            if (!hasImages) {
                byte[] best = lossless.length > originalSize ? input : lossless;
                return new CompressBytesResult(best, originalSize, best.length, false);
            }

            // 3) Lossy image ladder, gentlest first; stop as soon as the target is met.
            //    Reload a pristine copy per rung (identical output to before), but decode each
            //    source image only once via decodeCache — see execute() for the rationale.
            DecodeCache decodeCache = new DecodeCache();
            for (Step step : STEPS) {
                try (PDDocument compressed = PdfLoader.load(input)) {
                    recompressImages(compressed, step.scale(), step.quality(), decodeCache);
                    if (SizeEstimator.estimateBytes(compressed) <= targetSizeBytes) {
                        byte[] out = PdfLoader.toBytes(compressed);
                        return new CompressBytesResult(out, originalSize, out.length, true);
                    }
                }
            }

            // 4) Target unreachable — most-compressed version, but never larger than original.
            try (PDDocument compressed = PdfLoader.load(input)) {
                Step strongest = STEPS.get(STEPS.size() - 1);
                recompressImages(compressed, strongest.scale(), strongest.quality(), decodeCache);
                byte[] out = SizeEstimator.estimateBytes(compressed) < originalSize
                    ? PdfLoader.toBytes(compressed) : input;
                return new CompressBytesResult(out, originalSize, out.length, false);
            }
        } catch (IOException e) {
            throw new PdfOperationException("Compression failed: " + e.getMessage(), e);
        }
    }

    private static boolean hasImages(PDDocument doc) throws IOException {
        for (PDPage page : doc.getPages()) {
            PDResources resources = page.getResources();
            if (resources == null) continue;
            for (COSName name : resources.getXObjectNames()) {
                if (resources.getXObject(name) instanceof PDImageXObject) return true;
            }
        }
        return false;
    }

    /**
     * Memoises the decoded raster of each <em>source</em> image across the ladder's per-rung
     * reloads. Every rung reloads a pristine copy of the same PDF, so the image sitting at a
     * given (page index, resource name) slot is the identical source stream each time and
     * decodes to the identical raster — decoding it once and reusing it is behaviour-preserving.
     *
     * <p>The one case that must <em>not</em> be cached is a resource dictionary shared by more
     * than one page: after the first page rewrites the shared slot to a JPEG, a later page sees
     * that freshly-made JPEG (not the original) and re-encodes it — quality that varies per rung.
     * {@link #shouldCache} detects a repeat visit to the same (dictionary, name) within a rung and
     * forces a fresh decode there, exactly mirroring the pre-cache behaviour.
     */
    private static final class DecodeCache {
        private record SlotKey(int pageIndex, COSName name) {}

        private final Map<SlotKey, BufferedImage> bySlot = new HashMap<>();
        // Per-rung guard: (resource dictionary identity, name) slots already rewritten this rung.
        // Keyed on the COS dictionary (not the PDResources wrapper, which PDPage re-creates per
        // call) so two pages sharing one /Resources dictionary are recognised as the same slot.
        private Map<COSDictionary, Set<COSName>> visitedThisRung = new IdentityHashMap<>();

        /** Resets the per-rung visit tracking; call once at the start of each rung. */
        void beginRung() {
            visitedThisRung = new IdentityHashMap<>();
        }

        /** True if this (dictionary, name) slot is being rewritten for the first time this rung. */
        boolean shouldCache(PDResources resources, COSName name) {
            return visitedThisRung.computeIfAbsent(resources.getCOSObject(), r -> new HashSet<>())
                                  .add(name);
        }

        BufferedImage get(int pageIndex, COSName name) {
            return bySlot.get(new SlotKey(pageIndex, name));
        }

        void put(int pageIndex, COSName name, BufferedImage image) {
            bySlot.put(new SlotKey(pageIndex, name), image);
        }
    }

    /**
     * Re-encodes every image on every page as JPEG at {@code quality}, first scaling
     * it by {@code scale} (1.0 keeps the pixel dimensions but still re-encodes —
     * which is what shrinks losslessly-stored images). Images are never upscaled.
     *
     * <p>{@code decodeCache} supplies each source image's decoded raster, rasterising it only the
     * first time it is seen and reusing it on later rungs. The cached raster is only ever read
     * (drawn from), never mutated, so reuse yields byte-for-byte the same JPEG a fresh decode would.
     */
    private static void recompressImages(PDDocument doc, float scale, float quality,
                                         DecodeCache decodeCache)
            throws IOException {
        decodeCache.beginRung();
        int pageIndex = 0;
        for (PDPage page : doc.getPages()) {
            int currentPage = pageIndex++;
            PDResources resources = page.getResources();
            if (resources == null) continue;
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xobj = resources.getXObject(name);
                if (!(xobj instanceof PDImageXObject image)) continue;

                int newW = clampDimension(image.getWidth(), scale);
                int newH = clampDimension(image.getHeight(), scale);

                BufferedImage source;
                if (decodeCache.shouldCache(resources, name)) {
                    source = decodeCache.get(currentPage, name);
                    if (source == null) {
                        source = image.getImage();
                        decodeCache.put(currentPage, name, source);
                    }
                } else {
                    // A shared resource dictionary revisited this rung: the slot now holds the
                    // JPEG a previous page just wrote, so decode it fresh (never cache it).
                    source = image.getImage();
                }

                BufferedImage rgb = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                   RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(source, 0, 0, newW, newH, null);
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
