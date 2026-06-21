package com.pdfconduit.app.cli;

import com.pdfconduit.core.pipeline.PipelineExecutor;
import com.pdfconduit.core.pipeline.PipelineException;
import com.pdfconduit.core.pipeline.PipelineModel;
import com.pdfconduit.core.pipeline.PipelineStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "pipeline",
         description = "Run a saved pipeline (.json), as built in the GUI's Pipeline view.")
public class PipelineCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE", description = "Saved pipeline (.json).")
    private Path file;

    @Override
    public Integer call() {
        PipelineModel model;
        try {
            model = PipelineStore.load(file);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        try {
            PipelineExecutor.Result result = PipelineExecutor.run(model,
                (done, total, msg) -> System.out.printf("%s (%d/%d)%n", msg, done, total));
            int saved = result.savedByNode().values().stream().mapToInt(List::size).sum();
            System.out.printf("Pipeline finished — saved %d file(s).%n", saved);
            return 0;
        } catch (PipelineException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }
}
