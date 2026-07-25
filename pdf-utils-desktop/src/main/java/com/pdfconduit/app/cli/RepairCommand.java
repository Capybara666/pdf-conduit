package com.pdfconduit.app.cli;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.RepairFinding;
import com.pdfconduit.core.model.RepairOptions;
import com.pdfconduit.core.model.RepairResult;
import com.pdfconduit.core.operations.PdfRepairer;
import com.pdfconduit.core.service.OperationType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "repair",
         description = "Try to repair a damaged PDF — not every file can be recovered.")
public class RepairCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE", description = "Damaged PDF to rebuild.")
    private Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Output PDF path.")
    private Path output;

    @Override
    public Integer call() {
        try {
            Path out = output != null
                ? output : MergeCommand.deriveOutput(input, OperationType.REPAIR.suffix());
            RepairResult r = PdfRepairer.execute(new RepairOptions(input, out));
            if (!r.wasDamaged()) {
                System.out.printf("No damage found — rewrote %d pages → %s%n",
                    r.pageCount(), r.output());
            } else if (r.recovered()) {
                System.out.printf("Repaired (%s) — %d pages → %s%n",
                    findings(r), r.pageCount(), r.output());
            } else {
                System.out.printf(
                    "Partially repaired (%s) — %d pages → %s%n"
                    + "The rebuilt file still does not parse cleanly; not every file can be recovered.%n",
                    findings(r), r.pageCount(), r.output());
            }
            return 0;
        } catch (PdfOperationException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    private static String findings(RepairResult r) {
        return r.findings().isEmpty()
            ? "structure rebuilt"
            : r.findings().stream().map(RepairFinding::id).collect(Collectors.joining(", "));
    }
}
