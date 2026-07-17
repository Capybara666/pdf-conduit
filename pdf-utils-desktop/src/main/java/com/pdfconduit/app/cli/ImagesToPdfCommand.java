package com.pdfconduit.app.cli;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.MergeOptions;
import com.pdfconduit.core.model.MergeResult;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.PageSource;
import com.pdfconduit.core.operations.PdfMerger;
import com.pdfconduit.core.service.OperationType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "images-to-pdf", aliases = {"to-pdf"},
         description = "Combine images (and PDFs/documents) into one PDF. "
             + "Images are placed at the chosen page size; documents need LibreOffice.")
public class ImagesToPdfCommand implements Callable<Integer> {

    @Parameters(arity = "1..*", paramLabel = "FILE",
                description = "Files to combine: images (PNG, JPG, WEBP, TIFF, BMP), PDFs or documents.")
    private List<Path> images;

    @Option(names = "--page-size", paramLabel = "SIZE",
            description = "Page size for images: FIT (default), A4, A3, LETTER.", defaultValue = "FIT")
    private PageSize pageSize;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Output PDF path.")
    private Path output;

    @Override
    public Integer call() {
        List<Path> temps = new ArrayList<>();
        try {
            Path out = output != null ? output : MergeCommand.deriveOutput(images.get(0), OperationType.IMAGES_TO_PDF.suffix());
            List<PageSource> sources = CliSources.build(images, pageSize, temps);
            MergeResult result = PdfMerger.execute(new MergeOptions(sources, out));
            System.out.printf("Wrote %d page(s) → %s%n", result.pageCount(), result.output());
            return 0;
        } catch (PdfOperationException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        } finally {
            CliSources.deleteTemps(temps);
        }
    }
}
