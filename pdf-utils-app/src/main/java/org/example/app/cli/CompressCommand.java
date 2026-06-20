package org.example.app.cli;

import org.example.core.exception.PdfOperationException;
import org.example.core.model.CompressOptions;
import org.example.core.model.CompressResult;
import org.example.core.operations.PdfCompressor;
import org.example.core.service.OperationType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "compress",
         description = "Reduce PDF file size to a target. Example: --target-size 5MB")
public class CompressCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE", description = "Input PDF.")
    private Path input;

    @Option(names = "--target-size", required = true, paramLabel = "SIZE",
            converter = SizeConverter.class,
            description = "Target file size (e.g. 500KB, 5MB, 1.5MB).")
    private long targetSizeBytes;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Output PDF path.")
    private Path output;

    @Option(names = "--verbose") private boolean verbose;

    @Override
    public Integer call() {
        try {
            Path out = output != null ? output : MergeCommand.deriveOutput(input, OperationType.COMPRESS.suffix());
            CompressResult result = PdfCompressor.execute(new CompressOptions(input, targetSizeBytes, out));
            long kbResult = result.resultBytes() / 1024;
            long kbOrig   = result.originalBytes() / 1024;
            if (result.targetReached()) {
                System.out.printf("Compressed %d KB → %d KB → %s%n", kbOrig, kbResult, result.output());
            } else {
                System.err.printf(
                    "Warning: target unreachable. Best achieved: %d KB (original: %d KB) → %s%n",
                    kbResult, kbOrig, result.output());
            }
            return 0;
        } catch (PdfOperationException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }
}
