package com.pdfconduit.core.operations;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.ImageFormat;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PdfToImageOptions;
import com.pdfconduit.core.model.PdfToImageResult;
import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Renders a PDF's pages to raster image files (PNG or JPEG) — the reverse of
 * {@link ImageToPdfConverter}. Each selected page becomes
 * {@code <baseName>_pNNN.<ext>} inside the output folder, where {@code NNN} is the
 * source page number zero-padded to the document's page count.
 */
public final class PdfToImageConverter {

    private PdfToImageConverter() {}

    /**
     * Absolute hard ceiling on render DPI, applied regardless of caller input as a last line of
     * defence against an out-of-memory raster (a page rendered at, say, 60000 DPI would allocate
     * hundreds of gigabytes). Surfaces that enforce a tighter, configurable cap should still do so
     * before calling in; this only guarantees a single request cannot exhaust the JVM heap.
     */
    public static final int MAX_RENDER_DPI = 1200;

    public static PdfToImageResult execute(PdfToImageOptions opts) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(opts.input())) {
            int total = doc.getNumberOfPages();
            List<Integer> pageNums = opts.pages().isAll()
                ? IntStream.rangeClosed(1, total).boxed().toList()
                : opts.pages().pageNumbers();

            PDFRenderer renderer = new PDFRenderer(doc);
            int dpi = Math.min(MAX_RENDER_DPI, Math.max(1, opts.dpi()));
            int width = Integer.toString(total).length();
            Files.createDirectories(opts.outputDir());

            List<Path> outputs = new ArrayList<>(pageNums.size());
            for (int pageNum : pageNums) {
                // JPEG has no alpha; render onto RGB so the page background stays white.
                BufferedImage img = renderer.renderImageWithDPI(pageNum - 1, dpi, ImageType.RGB);
                Path file = opts.outputDir().resolve(
                    opts.baseName() + "_p" + pad(pageNum, width) + "." + opts.format().extension());
                write(img, opts.format(), opts.jpegQuality(), file);
                outputs.add(file);
            }
            return new PdfToImageResult(outputs);

        } catch (IOException e) {
            throw new PdfOperationException("Image export failed: " + e.getMessage(), e);
        }
    }

    /**
     * In-memory variant: render the selected pages of {@code pdf} to encoded image bytes
     * (PNG or JPEG), in page order.
     */
    public static List<byte[]> executeBytes(byte[] pdf, ImageFormat format, int dpi,
                                            PageRange pages, float jpegQuality)
            throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            int total = doc.getNumberOfPages();
            List<Integer> pageNums = pages.isAll()
                ? IntStream.rangeClosed(1, total).boxed().toList()
                : pages.pageNumbers();

            PDFRenderer renderer = new PDFRenderer(doc);
            int renderDpi = Math.min(MAX_RENDER_DPI, Math.max(1, dpi));
            List<byte[]> outputs = new ArrayList<>(pageNums.size());
            for (int pageNum : pageNums) {
                BufferedImage img = renderer.renderImageWithDPI(pageNum - 1, renderDpi, ImageType.RGB);
                outputs.add(encode(img, format, jpegQuality));
            }
            return outputs;
        } catch (IOException e) {
            throw new PdfOperationException("Image export failed: " + e.getMessage(), e);
        }
    }

    private static void write(BufferedImage img, ImageFormat format, float quality, Path file)
            throws IOException {
        Files.write(file, encode(img, format, quality));
    }

    /** Encodes {@code img} to bytes in {@code format} (JPEG honours {@code quality}). */
    private static byte[] encode(BufferedImage img, ImageFormat format, float quality)
            throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (!format.isLossy()) {
            ImageIO.write(img, format.imageioName(), buf);
            return buf.toByteArray();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format.imageioName()).next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(Math.max(0.1f, Math.min(1f, quality)));
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(buf)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return buf.toByteArray();
    }

    private static String pad(int n, int width) {
        return String.format("%0" + Math.max(1, width) + "d", n);
    }
}
