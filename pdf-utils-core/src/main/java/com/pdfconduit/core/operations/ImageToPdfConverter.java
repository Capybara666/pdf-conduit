package com.pdfconduit.core.operations;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.ImageToPdfOptions;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.PdfResult;
import com.pdfconduit.core.util.GrayscaleConverter;
import com.pdfconduit.core.util.OutputPaths;
import com.pdfconduit.core.util.PdfLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class ImageToPdfConverter {

    private ImageToPdfConverter() {}

    public static PdfResult execute(ImageToPdfOptions opts) throws PdfOperationException {
        try (PDDocument doc = new PDDocument()) {
            for (Path imagePath : opts.images()) {
                appendImagePage(doc, imagePath, opts.pageSize(), opts.grayscale());
            }
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new PdfResult(opts.output(), doc.getNumberOfPages());
        } catch (IOException e) {
            throw new PdfOperationException("Image-to-PDF conversion failed: " + e.getMessage(), e);
        }
    }

    /**
     * In-memory variant: place each image in {@code images} on its own page (at
     * {@code pageSize}) and return the assembled PDF's bytes.
     */
    public static byte[] executeBytes(List<byte[]> images, PageSize pageSize)
            throws PdfOperationException {
        return executeBytes(images, pageSize, false);
    }

    /**
     * In-memory variant with an explicit grayscale toggle: when {@code grayscale} is true every
     * image is converted to grayscale (regardless of its source colorspace) before placement.
     */
    public static byte[] executeBytes(List<byte[]> images, PageSize pageSize, boolean grayscale)
            throws PdfOperationException {
        try (PDDocument doc = new PDDocument()) {
            for (byte[] image : images) {
                BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(image));
                if (bufferedImage == null) {
                    throw new IOException("Cannot read image: unsupported or corrupt image data.");
                }
                appendImagePage(doc, bufferedImage, pageSize, grayscale);
            }
            return PdfLoader.toBytes(doc);
        } catch (IOException e) {
            throw new PdfOperationException("Image-to-PDF conversion failed: " + e.getMessage(), e);
        }
    }

    static void appendImagePage(PDDocument doc, Path imagePath, PageSize targetSize)
            throws IOException {
        appendImagePage(doc, imagePath, targetSize, false);
    }

    static void appendImagePage(PDDocument doc, Path imagePath, PageSize targetSize, boolean grayscale)
            throws IOException {
        BufferedImage bufferedImage = ImageIO.read(imagePath.toFile());
        if (bufferedImage == null) {
            throw new IOException("Cannot read image: " + imagePath);
        }
        appendImagePage(doc, bufferedImage, targetSize, grayscale);
    }

    /** The shared layout algorithm: place {@code bufferedImage} on a new page sized per {@code targetSize}. */
    static void appendImagePage(PDDocument doc, BufferedImage bufferedImage, PageSize targetSize)
            throws IOException {
        appendImagePage(doc, bufferedImage, targetSize, false);
    }

    static void appendImagePage(PDDocument doc, BufferedImage bufferedImage, PageSize targetSize,
                                boolean grayscale) throws IOException {
        if (grayscale) {
            bufferedImage = GrayscaleConverter.toGrayscale(bufferedImage);
        }
        PDImageXObject img = LosslessFactory.createFromImage(doc, bufferedImage);

        PDRectangle mediaBox = resolveMediaBox(targetSize, bufferedImage.getWidth(), bufferedImage.getHeight());
        PDPage page = new PDPage(mediaBox);
        doc.addPage(page);

        float[] drawParams = fitInBox(
            bufferedImage.getWidth(), bufferedImage.getHeight(),
            mediaBox.getWidth(), mediaBox.getHeight()
        );

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(img, drawParams[0], drawParams[1], drawParams[2], drawParams[3]);
        }
    }

    private static PDRectangle resolveMediaBox(PageSize targetSize, int imgW, int imgH) {
        return switch (targetSize) {
            case FIT    -> new PDRectangle(imgW, imgH);
            case A4     -> PDRectangle.A4;
            case A3     -> PDRectangle.A3;
            case LETTER -> PDRectangle.LETTER;
        };
    }

    private static float[] fitInBox(float imgW, float imgH, float boxW, float boxH) {
        float scale = Math.min(boxW / imgW, boxH / imgH);
        float drawW = imgW * scale;
        float drawH = imgH * scale;
        float x = (boxW - drawW) / 2f;
        float y = (boxH - drawH) / 2f;
        return new float[]{x, y, drawW, drawH};
    }
}
