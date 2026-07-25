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
 *
 * <p>Colour handling is configurable via the options / arguments:
 * <ul>
 *   <li><b>transparentBackground</b> (PNG only) — render onto {@link ImageType#ARGB} so the page
 *       background stays transparent instead of white. Silently ignored for JPEG, which has no
 *       alpha channel.</li>
 *   <li><b>grayscale</b> — render in grayscale instead of colour.</li>
 * </ul>
 * <b>Precedence:</b> when a PNG requests both transparency and grayscale, transparency wins the
 * render type ({@code ARGB}, since there is no single "gray + alpha" {@link ImageType}) and
 * grayscale is then applied as a pixel post-process that preserves the alpha channel — yielding a
 * transparent, desaturated PNG. Grayscale-only (or any JPEG) renders directly as
 * {@link ImageType#GRAY}; the default (neither flag) is opaque colour {@link ImageType#RGB}.
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
                BufferedImage img = render(renderer, pageNum - 1, dpi, opts.format(),
                    opts.transparentBackground(), opts.grayscale());
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
     * (PNG or JPEG), in page order. Backward-compatible overload — opaque colour output.
     */
    public static List<byte[]> executeBytes(byte[] pdf, ImageFormat format, int dpi,
                                            PageRange pages, float jpegQuality)
            throws PdfOperationException {
        return executeBytes(pdf, format, dpi, pages, jpegQuality, false, false);
    }

    /**
     * In-memory variant with colour options: render the selected pages of {@code pdf} to encoded
     * image bytes (PNG or JPEG), in page order. See the class doc for the
     * transparency/grayscale precedence rules.
     */
    public static List<byte[]> executeBytes(byte[] pdf, ImageFormat format, int dpi,
                                            PageRange pages, float jpegQuality,
                                            boolean transparentBackground, boolean grayscale)
            throws PdfOperationException {
        return executeBytes(pdf, format, dpi, pages, jpegQuality, transparentBackground, grayscale, null);
    }

    /**
     * As {@link #executeBytes(byte[], ImageFormat, int, PageRange, float, boolean, boolean)}, but
     * reporting the accumulated encoded size to {@code outputGuard} after every page. Every page's
     * image is held in the heap until the caller is done, so a caller with a memory budget (the web
     * backend) can abort a runaway render early instead of OOM-ing at the end. {@code null} ⇒
     * unbounded (the desktop/CLI default).
     */
    public static List<byte[]> executeBytes(byte[] pdf, ImageFormat format, int dpi,
                                            PageRange pages, float jpegQuality,
                                            boolean transparentBackground, boolean grayscale,
                                            com.pdfconduit.core.service.OutputSizeGuard outputGuard)
            throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            int total = doc.getNumberOfPages();
            List<Integer> pageNums = pages.isAll()
                ? IntStream.rangeClosed(1, total).boxed().toList()
                : pages.pageNumbers();

            PDFRenderer renderer = new PDFRenderer(doc);
            int renderDpi = Math.min(MAX_RENDER_DPI, Math.max(1, dpi));
            List<byte[]> outputs = new ArrayList<>(pageNums.size());
            long accumulated = 0;
            for (int pageNum : pageNums) {
                BufferedImage img = render(renderer, pageNum - 1, renderDpi, format,
                    transparentBackground, grayscale);
                byte[] encoded = encode(img, format, jpegQuality);
                outputs.add(encoded);
                accumulated += encoded.length;
                if (outputGuard != null) outputGuard.check(accumulated);
            }
            return outputs;
        } catch (IOException e) {
            throw new PdfOperationException("Image export failed: " + e.getMessage(), e);
        }
    }

    /**
     * Renders one page, honouring transparency (PNG only) and grayscale. When both apply, renders
     * ARGB then desaturates in place so the alpha channel survives (see class doc).
     */
    private static BufferedImage render(PDFRenderer renderer, int pageIndex, int dpi,
                                        ImageFormat format, boolean transparentBackground,
                                        boolean grayscale) throws IOException {
        // Transparency only makes sense for a format with an alpha channel (PNG).
        boolean transparent = transparentBackground && format == ImageFormat.PNG;
        ImageType type;
        if (transparent) {
            type = ImageType.ARGB;                        // transparent background; keep colour data
        } else if (grayscale) {
            type = ImageType.GRAY;                         // direct grayscale render
        } else {
            type = ImageType.RGB;                          // default: opaque colour, white background
        }
        BufferedImage img = renderer.renderImageWithDPI(pageIndex, dpi, type);
        if (transparent && grayscale) {
            desaturateKeepingAlpha(img);                   // gray + alpha (no single ImageType exists)
        }
        return img;
    }

    /** Desaturates an ARGB image to grayscale in place, preserving each pixel's alpha. */
    private static void desaturateKeepingAlpha(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                // Rec. 601 luma.
                int lum = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
                int gray = (a << 24) | (lum << 16) | (lum << 8) | lum;
                img.setRGB(x, y, gray);
            }
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
