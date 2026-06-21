package com.pdfconduit.app.cli;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PdfResult;
import com.pdfconduit.core.model.ProtectOptions;
import com.pdfconduit.core.operations.PdfProtector;
import com.pdfconduit.core.service.OperationType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "protect",
         description = "Password-protect a PDF (AES-128 encryption).")
public class ProtectCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE", description = "Input PDF.")
    private Path input;

    @Option(names = "--password", required = true, paramLabel = "PWD",
            description = "Password required to open the PDF.")
    private String password;

    @Option(names = "--owner-password", paramLabel = "PWD",
            description = "Owner/permissions password (defaults to --password).")
    private String ownerPassword;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Output PDF path.")
    private Path output;

    @Override
    public Integer call() {
        try {
            Path out = output != null ? output : MergeCommand.deriveOutput(input, OperationType.PROTECT.suffix());
            PdfResult result = PdfProtector.execute(new ProtectOptions(
                input, password, ownerPassword == null ? "" : ownerPassword, out));
            System.out.printf("Protected %d page(s) → %s%n", result.pageCount(), result.output());
            return 0;
        } catch (PdfOperationException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }
}
