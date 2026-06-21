package com.pdfconduit.app.cli;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.MetadataOptions;
import com.pdfconduit.core.model.PdfMetadata;
import com.pdfconduit.core.model.PdfResult;
import com.pdfconduit.core.operations.PdfMetadataEditor;
import com.pdfconduit.core.service.OperationType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "metadata",
         description = "Show, edit, or strip a PDF's metadata (title/author/subject/keywords).")
public class MetadataCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE", description = "Input PDF.")
    private Path input;

    @Option(names = "--show", description = "Print the current metadata and exit.")
    private boolean show;

    @Option(names = "--title", paramLabel = "TEXT", description = "Set the title (empty clears it).")
    private String title;

    @Option(names = "--author", paramLabel = "TEXT", description = "Set the author (empty clears it).")
    private String author;

    @Option(names = "--subject", paramLabel = "TEXT", description = "Set the subject (empty clears it).")
    private String subject;

    @Option(names = "--keywords", paramLabel = "TEXT", description = "Set the keywords (empty clears it).")
    private String keywords;

    @Option(names = "--strip", description = "Remove all metadata (including XMP).")
    private boolean strip;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Output PDF path.")
    private Path output;

    @Override
    public Integer call() {
        try {
            if (show) {
                PdfMetadata md = PdfMetadataEditor.read(input);
                System.out.println("Title:    " + nz(md.title()));
                System.out.println("Author:   " + nz(md.author()));
                System.out.println("Subject:  " + nz(md.subject()));
                System.out.println("Keywords: " + nz(md.keywords()));
                return 0;
            }
            Path out = output != null ? output : MergeCommand.deriveOutput(input, OperationType.METADATA.suffix());
            PdfResult result = PdfMetadataEditor.execute(new MetadataOptions(
                input, title, author, subject, keywords, strip, out));
            System.out.printf("Updated metadata → %s%n", result.output());
            return 0;
        } catch (PdfOperationException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
