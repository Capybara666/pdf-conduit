package org.example.app.cli;

import org.example.core.exception.PdfOperationException;
import org.example.core.model.MergeOptions;
import org.example.core.model.MergeResult;
import org.example.core.model.PageRange;
import org.example.core.model.PageSize;
import org.example.core.model.PageSource;
import org.example.core.operations.PdfMerger;
import org.example.core.util.FileTypeDetector;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "merge", description = "Merge PDFs and/or images into one PDF.")
public class MergeCommand implements Callable<Integer> {

    @Parameters(arity = "1..*", paramLabel = "FILE",
                description = "Input files (PDF or image: PNG, JPG, WEBP, TIFF, BMP).")
    private List<Path> inputs;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Output PDF path.")
    private Path output;

    @Option(names = "--verbose") private boolean verbose;

    @Override
    public Integer call() {
        try {
            List<PageSource> sources = inputs.stream()
                .map(p -> FileTypeDetector.isPdf(p)
                    ? (PageSource) new PageSource.PdfPageSource(p, PageRange.ALL)
                    : new PageSource.ImageSource(p, PageSize.FIT))
                .toList();
            Path out = output != null ? output : deriveOutput(inputs.get(0), "_merged");
            MergeResult result = PdfMerger.execute(new MergeOptions(sources, out));
            System.out.printf("Merged %d pages → %s%n", result.pageCount(), result.output());
            return 0;
        } catch (PdfOperationException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    static Path deriveOutput(Path input, String suffix) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        String ext  = dot >= 0 ? name.substring(dot)    : ".pdf";
        return input.resolveSibling(base + suffix + ext);
    }
}
