package com.pdfconduit.app.cli;

import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.SplitMode;
import com.pdfconduit.core.model.SplitOptions;
import com.pdfconduit.core.model.SplitResult;
import com.pdfconduit.core.operations.PdfSplitter;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.core.util.PageRangeParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "split",
         description = "Extract pages from a PDF. Range syntax: 1, 2-5, 1,3,5-8, end-2.")
public class SplitCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE", description = "Input PDF.")
    private Path input;

    @Option(names = "--pages", paramLabel = "RANGE",
            description = "Pages to extract (default: all).", defaultValue = "")
    private String pages;

    @Option(names = {"-o", "--output"}, paramLabel = "PATH",
            description = "Output PDF file (default), or output folder with --separate.")
    private Path output;

    @Option(names = "--separate",
            description = "Write one PDF per page into the output folder instead of one combined PDF.")
    private boolean separate;

    @Option(names = "--verbose") private boolean verbose;

    @Override
    public Integer call() {
        try {
            PageRange range = pages.isBlank()
                ? PageRange.ALL
                : PageRangeParser.parse(pages, countPages(input));
            if (separate) {
                Path dir = output != null ? output : input.resolveSibling(stem(input) + "_pages");
                SplitResult result = PdfSplitter.execute(
                    new SplitOptions(input, range, SplitMode.SEPARATE, dir));
                System.out.printf("Wrote %d file(s) → %s%n", result.fileCount(), dir);
                return 0;
            }
            Path out = output != null ? output : MergeCommand.deriveOutput(input, OperationType.EXTRACT.suffix());
            SplitResult result = PdfSplitter.execute(new SplitOptions(input, range, out));
            System.out.printf("Extracted %d pages → %s%n", result.pageCount(), result.output());
            return 0;
        } catch (InvalidPageRangeException e) {
            System.err.println("Invalid page range: " + e.getMessage());
            return 1;
        } catch (PdfOperationException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
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
