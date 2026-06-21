package com.pdfconduit.app.cli;

import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.RotateOptions;
import com.pdfconduit.core.model.RotateResult;
import com.pdfconduit.core.operations.PdfRotator;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.core.util.PageRangeParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "rotate", description = "Rotate pages in a PDF by 90, 180, or 270 degrees.")
public class RotateCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE", description = "Input PDF.")
    private Path input;

    @Option(names = "--pages", paramLabel = "RANGE",
            description = "Pages to rotate (default: all). E.g. 1,3,5-8", defaultValue = "")
    private String pages;

    @Option(names = "--angle", required = true, paramLabel = "DEGREES",
            description = "Rotation angle: 90, 180, or 270.")
    private int angle;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Output PDF path.")
    private Path output;

    @Override
    public Integer call() {
        if (angle != 90 && angle != 180 && angle != 270) {
            System.err.println("Error: --angle must be 90, 180, or 270.");
            return 1;
        }
        try {
            PageRange range = pages.isBlank()
                ? PageRange.ALL
                : PageRangeParser.parse(pages, countPages(input));
            Path out = output != null ? output : MergeCommand.deriveOutput(input, OperationType.ROTATE.suffix());
            RotateResult result = PdfRotator.execute(new RotateOptions(input, range, angle, out));
            System.out.printf("Rotated %d pages by %d° → %s%n",
                result.rotatedPageCount(), angle, result.output());
            return 0;
        } catch (InvalidPageRangeException e) {
            System.err.println("Invalid page range: " + e.getMessage());
            return 1;
        } catch (PdfOperationException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    private static int countPages(Path pdf) throws PdfOperationException {
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }
}
