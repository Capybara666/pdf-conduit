package com.pdfconduit.core.operations;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.ImageFormat;
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

    public static PdfToImageResult execute(PdfToImageOptions opts) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(opts.input())) {
            int total = doc.getNumberOfPages();
            List<Integer> pageNums = opts.pages().isAll()
                ? IntStream.rangeClosed(1, total).boxed().toList()
                : opts.pages().pageNumbers();

            PDFRenderer renderer = new PDFRenderer(doc);
            int dpi = Math.max(1, opts.dpi());
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

    private static void write(BufferedImage img, ImageFormat format, float quality, Path file)
            throws IOException {
        if (!format.isLossy()) {
            ImageIO.write(img, format.imageioName(), file.toFile());
            return;
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format.imageioName()).next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(Math.max(0.1f, Math.min(1f, quality)));
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static String pad(int n, int width) {
        return String.format("%0" + Math.max(1, width) + "d", n);
    }
}
