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
import com.pdfconduit.core.util.OutputPaths;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

public final class ImageToPdfConverter {

    private ImageToPdfConverter() {}

    public static PdfResult execute(ImageToPdfOptions opts) throws PdfOperationException {
        try (PDDocument doc = new PDDocument()) {
            for (Path imagePath : opts.images()) {
                appendImagePage(doc, imagePath, opts.pageSize());
            }
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new PdfResult(opts.output(), doc.getNumberOfPages());
        } catch (IOException e) {
            throw new PdfOperationException("Image-to-PDF conversion failed: " + e.getMessage(), e);
        }
    }

    static void appendImagePage(PDDocument doc, Path imagePath, PageSize targetSize)
            throws IOException {
        BufferedImage bufferedImage = ImageIO.read(imagePath.toFile());
        if (bufferedImage == null) {
            throw new IOException("Cannot read image: " + imagePath);
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
