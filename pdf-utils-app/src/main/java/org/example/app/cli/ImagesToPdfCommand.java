package org.example.app.cli;

import org.example.core.exception.PdfOperationException;
import org.example.core.model.ImageToPdfOptions;
import org.example.core.model.PageSize;
import org.example.core.model.PdfResult;
import org.example.core.operations.ImageToPdfConverter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "images-to-pdf",
         description = "Convert image files (PNG, JPG, WEBP, TIFF, BMP) to a PDF.")
public class ImagesToPdfCommand implements Callable<Integer> {

    @Parameters(arity = "1..*", paramLabel = "IMAGE",
                description = "Image files to convert.")
    private List<Path> images;

    @Option(names = "--page-size", paramLabel = "SIZE",
            description = "Page size: FIT (default), A4, A3, LETTER.", defaultValue = "FIT")
    private PageSize pageSize;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Output PDF path.")
    private Path output;

    @Override
    public Integer call() {
        try {
            Path out = output != null ? output : MergeCommand.deriveOutput(images.get(0), "_converted");
            if (!out.toString().endsWith(".pdf")) {
                out = Path.of(out + ".pdf");
            }
            PdfResult result = ImageToPdfConverter.execute(
                new ImageToPdfOptions(images, pageSize, out));
            System.out.printf("Converted %d image(s) → %s%n", result.pageCount(), result.output());
            return 0;
        } catch (PdfOperationException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }
}
