package org.example.app.cli;

import org.example.core.exception.InvalidPageRangeException;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.ArrangeOptions;
import org.example.core.model.ArrangeResult;
import org.example.core.operations.PdfArranger;
import org.example.core.service.OperationType;
import org.example.core.util.PageOrderParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "arrange",
    description = "Reorder the pages of a PDF. Pages may be moved, repeated or dropped.")
public class ArrangeCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE", description = "Input PDF.")
    private Path input;

    @Option(names = "--order", required = true, paramLabel = "ORDER",
            description = "New page order, e.g. 3,1,2 or 5-1 (reverse) or end,1-3. "
                + "Repeat a page to duplicate it; omit a page to drop it.")
    private String order;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Output PDF path.")
    private Path output;

    @Override
    public Integer call() {
        try {
            List<Integer> pageOrder = PageOrderParser.parse(order, countPages(input));
            Path out = output != null ? output : MergeCommand.deriveOutput(input, OperationType.ARRANGE.suffix());
            ArrangeResult result = PdfArranger.execute(new ArrangeOptions(input, pageOrder, out));
            System.out.printf("Arranged %d pages → %s%n", result.pageCount(), result.output());
            return 0;
        } catch (InvalidPageRangeException e) {
            System.err.println("Invalid page order: " + e.getMessage());
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
