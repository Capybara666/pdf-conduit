package com.pdfconduit.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import com.pdfconduit.core.model.CompressBytesResult;
import com.pdfconduit.core.model.CompressOptions;
import com.pdfconduit.core.model.CompressOptions.DpiPreset;
import com.pdfconduit.core.model.CompressResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfCompressorTest {

    @TempDir Path tmp;

    @Test
    void compressesImageHeavyPdf() throws Exception {
        Path src = createImageHeavyPdf();
        long originalSize = src.toFile().length();
        Path out = tmp.resolve("compressed.pdf");

        CompressResult result = PdfCompressor.execute(
            new CompressOptions(src, 1024, out)); // 1 KB — unreachable, triggers compression

        assertTrue(out.toFile().exists());
        assertTrue(result.resultBytes() <= originalSize);
        assertFalse(result.targetReached());
        assertEquals(out, result.output());
    }

    @Test
    void reachesTargetForSmallFile() throws Exception {
        Path src = createTextPdf(2);
        Path out = tmp.resolve("small.pdf");

        CompressResult result = PdfCompressor.execute(
            new CompressOptions(src, 100 * 1024, out)); // 100 KB — easily reachable

        assertTrue(result.targetReached());
        assertTrue(result.resultBytes() <= 100 * 1024);
    }

    @Test
    void modestTargetReducesQualityBeforeDownscaling() throws Exception {
        // A smooth image stored losslessly: re-encoding it as JPEG at full
        // resolution already shrinks it well below a modest target, so the
        // compressor should NOT need to reduce the image's pixel dimensions.
        Path src = createSmoothImagePdf(1200);
        long original = src.toFile().length();
        Path out = tmp.resolve("modest.pdf");

        long target = original * 8 / 10; // 80% — reachable by quality reduction alone
        CompressResult result = PdfCompressor.execute(new CompressOptions(src, target, out));

        assertTrue(result.targetReached(), "a modest target should be reached");
        assertTrue(result.resultBytes() <= target);
        assertEquals(1200, firstImageWidth(out),
            "a modest target must be met by lowering quality, not by downscaling the image");
    }

    // ----------------------------------------------------------- DPI presets + grayscale

    @Test
    void printPresetCapsResolutionAndShrinks() throws Exception {
        // A 1200 px image displayed across 144 pt (2 in) ⇒ 600 DPI.
        Path src = createHighDpiImagePdf(1200, 144f);
        long original = src.toFile().length();
        Path out = tmp.resolve("print.pdf");

        // Generous target (= original) so size is not the binding constraint — the DPI cap is.
        CompressResult r = PdfCompressor.execute(
            new CompressOptions(src, original, out, DpiPreset.PRINT, false));

        assertTrue(out.toFile().exists());
        assertTrue(isValidPdf(out), "PRINT-preset output must be a valid PDF");
        assertTrue(r.resultBytes() < original, "capping 600 DPI to 300 must shrink the file");
        // 300 / 600 = 0.5 ⇒ half the pixel width.
        assertEquals(600, firstImageWidth(out), "PRINT (300 DPI) should halve a 600 DPI image");
    }

    @Test
    void ebookPresetDownscalesMoreThanPrint() throws Exception {
        Path src = createHighDpiImagePdf(1200, 144f); // 600 DPI
        long original = src.toFile().length();
        Path ebook = tmp.resolve("ebook.pdf");
        Path print = tmp.resolve("print2.pdf");

        CompressResult e = PdfCompressor.execute(
            new CompressOptions(src, original, ebook, DpiPreset.EBOOK, false));
        CompressResult p = PdfCompressor.execute(
            new CompressOptions(src, original, print, DpiPreset.PRINT, false));

        assertEquals(300, firstImageWidth(ebook), "EBOOK (150 DPI) should quarter a 600 DPI image");
        assertEquals(600, firstImageWidth(print));
        assertTrue(e.resultBytes() < p.resultBytes(), "EBOOK must be smaller than PRINT");
    }

    @Test
    void grayscaleProducesValidSingleComponentOutput() throws Exception {
        Path src = createHighDpiImagePdf(800, 200f);
        long original = src.toFile().length();
        Path out = tmp.resolve("gray.pdf");

        CompressResult r = PdfCompressor.execute(
            new CompressOptions(src, original, out, DpiPreset.NONE, true));

        assertTrue(isValidPdf(out), "grayscale output must be a valid PDF");
        assertTrue(r.resultBytes() <= original, "grayscale output must never be larger than input");
        assertEquals(1, firstImageComponents(out),
            "grayscale re-encode should leave a single-component (DeviceGray) image");
    }

    @Test
    void compressBytesHonoursDpiPreset() throws Exception {
        byte[] input = Files.readAllBytes(createHighDpiImagePdf(1200, 144f)); // 600 DPI
        CompressBytesResult r = PdfCompressor.compressBytes(
            input, input.length, DpiPreset.PRINT, false, null);

        assertTrue(r.bytes().length <= input.length);
        try (PDDocument doc = Loader.loadPDF(r.bytes())) {
            assertEquals(600, firstImageWidth(doc), "bytes API PRINT preset should halve a 600 DPI image");
        }
    }

    private boolean isValidPdf(Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages() > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Number of colour components of the first embedded image (1 ⇒ grayscale). */
    private int firstImageComponents(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDResources res = doc.getPage(0).getResources();
            for (COSName name : res.getXObjectNames()) {
                if (res.getXObject(name) instanceof PDImageXObject img) {
                    return img.getColorSpace().getNumberOfComponents();
                }
            }
        }
        return -1;
    }

    private int firstImageWidth(PDDocument doc) throws IOException {
        PDResources res = doc.getPage(0).getResources();
        for (COSName name : res.getXObjectNames()) {
            if (res.getXObject(name) instanceof PDImageXObject img) return img.getWidth();
        }
        return -1;
    }

    /** An A4 page with a {@code px}×{@code px} smooth image drawn across {@code drawPoints} pt. */
    private Path createHighDpiImagePdf(int px, float drawPoints) throws IOException {
        Path path = tmp.resolve("highdpi-" + px + "-" + drawPoints + ".pdf");
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                int r = (int) ((Math.sin(x * 0.04) + 1) * 120);
                int g = (int) ((Math.sin(y * 0.05) + 1) * 120);
                int b = (int) ((Math.sin((x + y) * 0.03) + 1) * 120);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject pdfImg = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdfImg, 50, 50, drawPoints, drawPoints);
            }
            doc.save(path.toFile());
        }
        return path;
    }

    /** Width (in pixels) of the first image embedded on page 1 of {@code pdf}. */
    private int firstImageWidth(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDResources res = doc.getPage(0).getResources();
            for (COSName name : res.getXObjectNames()) {
                PDXObject xobj = res.getXObject(name);
                if (xobj instanceof PDImageXObject img) return img.getWidth();
            }
        }
        return -1;
    }

    /** An A4 page filled with a smooth, JPEG-friendly image stored losslessly. */
    private Path createSmoothImagePdf(int px) throws IOException {
        Path path = tmp.resolve("smooth.pdf");
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                int r = (int) ((Math.sin(x * 0.04) + 1) * 120);
                int g = (int) ((Math.sin(y * 0.05) + 1) * 120);
                int b = (int) ((Math.sin((x + y) * 0.03) + 1) * 120);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject pdfImg = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdfImg, 0, 0, 595, 842);
            }
            doc.save(path.toFile());
        }
        return path;
    }

    @Test
    void losslesslyShrinksAnUncompressedPdf() throws Exception {
        // An image-free PDF saved without object streams (so it is larger than it
        // needs to be). Compression should shrink it purely by re-saving with
        // object-stream compression — no images, no quality loss.
        Path src = tmp.resolve("uncompressed.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 30; i++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(src.toFile(), CompressParameters.NO_COMPRESSION);
        }
        long original = src.toFile().length();
        Path out = tmp.resolve("lossless.pdf");

        CompressResult result = PdfCompressor.execute(new CompressOptions(src, original - 1, out));

        assertTrue(result.targetReached(), "a sub-original target should be met losslessly");
        assertTrue(result.resultBytes() < original, "object-stream compression should shrink it");
    }

    @Test
    void grayscaleConvertsNonRgbColorImages() throws Exception {
        // Indexed/palette and ARGB sources are exactly the colorspaces the old "draw straight onto a
        // TYPE_BYTE_GRAY canvas" path mishandled. Route through GrayscaleConverter and every one must
        // come out truly gray (R==G==B) — verified on the DECODED output image, not just its
        // component count — while still retaining the tonal variation from the original colours.
        for (int type : new int[]{BufferedImage.TYPE_BYTE_INDEXED, BufferedImage.TYPE_INT_ARGB}) {
            Path src = createColorImagePdf(240, type);
            long original = src.toFile().length();
            Path out = tmp.resolve("gray-nonrgb-" + type + ".pdf");

            PdfCompressor.execute(new CompressOptions(src, original, out, DpiPreset.NONE, true));

            assertTrue(isValidPdf(out), "grayscale output must be a valid PDF (type=" + type + ")");
            BufferedImage decoded = firstImage(out);
            assertNotNull(decoded, "output must still contain an image (type=" + type + ")");

            boolean sawVariation = false;
            int prev = -1;
            for (int y = 0; y < decoded.getHeight(); y += 7) {
                for (int x = 0; x < decoded.getWidth(); x += 7) {
                    int argb = decoded.getRGB(x, y);
                    int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                    assertEquals(r, g, "R==G expected (grayscale) type=" + type + " @" + x + "," + y);
                    assertEquals(g, b, "G==B expected (grayscale) type=" + type + " @" + x + "," + y);
                    if (prev != -1 && r != prev) sawVariation = true;
                    prev = r;
                }
            }
            assertTrue(sawVariation,
                "grayscale must preserve tonal variation from the colours, not be a flat fill (type="
                    + type + ")");
        }
    }

    /** Decoded first embedded image on page 1 of {@code pdf} (null if none). */
    private BufferedImage firstImage(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDResources res = doc.getPage(0).getResources();
            for (COSName name : res.getXObjectNames()) {
                if (res.getXObject(name) instanceof PDImageXObject img) return img.getImage();
            }
        }
        return null;
    }

    /** An A4 page with a {@code px}×{@code px} colour gradient stored in the given {@link BufferedImage} type. */
    private Path createColorImagePdf(int px, int imageType) throws IOException {
        Path path = tmp.resolve("color-" + imageType + ".pdf");
        BufferedImage img = new BufferedImage(px, px, imageType);
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                int r = (x * 255) / px;
                int g = (y * 255) / px;
                int b = ((x + y) * 255) / (2 * px);
                img.setRGB(x, y, (0xFF << 24) | (r << 16) | (g << 8) | b);
            }
        }
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject pdfImg = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdfImg, 50, 50, 200, 200);
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private Path createImageHeavyPdf() throws IOException {
        Path path = tmp.resolve("heavy.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < 2; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                BufferedImage img = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                g.setColor(new Color(p * 80, 100, 200));
                g.fillRect(0, 0, 1000, 1000);
                g.dispose();
                PDImageXObject pdfImg = LosslessFactory.createFromImage(doc, img);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(pdfImg, 0, 0, 595, 842);
                }
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private Path createTextPdf(int pages) throws IOException {
        Path path = tmp.resolve("text-" + pages + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(path.toFile());
        }
        return path;
    }
}
