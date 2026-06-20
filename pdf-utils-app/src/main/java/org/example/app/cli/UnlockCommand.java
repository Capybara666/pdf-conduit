package org.example.app.cli;

import org.example.core.exception.PdfOperationException;
import org.example.core.model.PdfResult;
import org.example.core.model.UnlockOptions;
import org.example.core.operations.PdfUnlocker;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "unlock",
         description = "Remove password protection from a PDF.")
public class UnlockCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE", description = "Input (protected) PDF.")
    private Path input;

    @Option(names = "--password", required = true, paramLabel = "PWD",
            description = "Password that opens the PDF.")
    private String password;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Output PDF path.")
    private Path output;

    @Override
    public Integer call() {
        try {
            Path out = output != null ? output : MergeCommand.deriveOutput(input, "_unlocked");
            PdfResult result = PdfUnlocker.execute(new UnlockOptions(input, password, out));
            System.out.printf("Unlocked %d page(s) → %s%n", result.pageCount(), result.output());
            return 0;
        } catch (PdfOperationException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }
}
