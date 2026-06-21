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

@Command(name = "merge",
         description = "Merge PDFs, images and documents into one PDF. "
             + "Office/text documents are converted via LibreOffice if installed.")
public class MergeCommand implements Callable<Integer> {

    @Parameters(arity = "1..*", paramLabel = "FILE",
                description = "Input files: PDF, image (PNG, JPG, WEBP, TIFF, BMP) "
                    + "or document (DOCX, ODT, RTF, TXT, XLSX, PPTX, …).")
    private List<Path> inputs;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Output PDF path.")
    private Path output;

    @Option(names = "--verbose") private boolean verbose;

    @Override
    public Integer call() {
        List<Path> temps = new ArrayList<>();
        try {
            List<PageSource> sources = CliSources.build(inputs, PageSize.FIT, temps);
            Path out = output != null ? output : deriveOutput(inputs.get(0), OperationType.MERGE.suffix());
            MergeResult result = PdfMerger.execute(new MergeOptions(sources, out));
            System.out.printf("Merged %d pages → %s%n", result.pageCount(), result.output());
            return 0;
        } catch (PdfOperationException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        } finally {
            CliSources.deleteTemps(temps);
        }
    }

    /** Default output beside the first input, always a {@code .pdf} ({@code <stem><suffix>.pdf}). */
    static Path deriveOutput(Path input, String suffix) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        return input.resolveSibling(base + suffix + ".pdf");
    }
}
