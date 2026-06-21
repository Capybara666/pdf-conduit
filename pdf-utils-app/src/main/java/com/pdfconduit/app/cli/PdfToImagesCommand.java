package com.pdfconduit.app.cli;

import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.ImageFormat;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PdfToImageOptions;
import com.pdfconduit.core.model.PdfToImageResult;
import com.pdfconduit.core.operations.PdfToImageConverter;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.core.util.PageRangeParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "pdf-to-images", aliases = {"to-images"},
         description = "Export a PDF's pages as PNG or JPEG images into a folder.")
public class PdfToImagesCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE", description = "Input PDF.")
    private Path input;

    @Option(names = "--format", paramLabel = "png|jpeg", defaultValue = "png",
            description = "Image format (default: png).")
    private String format;

    @Option(names = "--dpi", paramLabel = "N", defaultValue = "150",
            description = "Render resolution in DPI (default: 150).")
    private int dpi;

    @Option(names = "--quality", paramLabel = "0-1", defaultValue = "0.85",
            description = "JPEG quality, 0..1 (ignored for PNG; default: 0.85).")
    private float quality;

    @Option(names = "--pages", paramLabel = "RANGE", defaultValue = "",
            description = "Pages to export, e.g. 1,3,5-8 (default: all).")
    private String pages;

    @Option(names = {"-o", "--output"}, paramLabel = "DIR",
            description = "Output folder (default: <name>_images next to the input).")
    private Path output;

    @Override
    public Integer call() {
        try {
            ImageFormat fmt = parseFormat(format);
            PageRange range = pages.isBlank()
                ? PageRange.ALL
                : PageRangeParser.parse(pages, countPages(input));
            Path dir = output != null
                ? output
                : input.resolveSibling(stem(input) + OperationType.PDF_TO_IMAGES.suffix());

            PdfToImageResult result = PdfToImageConverter.execute(
                new PdfToImageOptions(input, fmt, dpi, range, quality, dir, stem(input)));
            System.out.printf("Wrote %d image(s) → %s%n", result.count(), dir);
            return 0;
        } catch (InvalidPageRangeException e) {
            System.err.println("Invalid page range: " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        } catch (PdfOperationException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    private static ImageFormat parseFormat(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "png" -> ImageFormat.PNG;
            case "jpg", "jpeg" -> ImageFormat.JPEG;
            default -> throw new IllegalArgumentException("Unknown format '" + s + "' (use png or jpeg).");
        };
    }

    private static String stem(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static int countPages(Path pdf) throws PdfOperationException {
        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                 org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }
}
