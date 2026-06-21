package com.pdfconduit.app.cli;

import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.PdfToTextOptions;
import com.pdfconduit.core.model.PdfToTextResult;
import com.pdfconduit.core.model.TextFormat;
import com.pdfconduit.core.operations.PdfTextExporter;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.core.util.PageRangeParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "pdf-to-text", aliases = {"to-text"},
         description = "Export a PDF's text as TXT (PDFBox) or Word .docx (LibreOffice).")
public class PdfToTextCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE", description = "Input PDF.")
    private Path input;

    @Option(names = "--format", paramLabel = "txt|docx", defaultValue = "txt",
            description = "Output format (default: txt). docx needs LibreOffice.")
    private String format;

    @Option(names = "--pages", paramLabel = "RANGE", defaultValue = "",
            description = "Pages to extract for txt, e.g. 1,3,5-8 (default: all; ignored for docx).")
    private String pages;

    @Option(names = {"-o", "--output"}, paramLabel = "DIR",
            description = "Output folder (default: <name>_text next to the input).")
    private Path output;

    @Override
    public Integer call() {
        try {
            TextFormat fmt = parseFormat(format);
            PageRange range = (fmt == TextFormat.TXT && !pages.isBlank())
                ? PageRangeParser.parse(pages, countPages(input))
                : PageRange.ALL;
            Path dir = output != null
                ? output
                : input.resolveSibling(stem(input) + OperationType.PDF_TO_TEXT.suffix());

            PdfToTextResult result = PdfTextExporter.execute(
                new PdfToTextOptions(input, fmt, range, dir, stem(input)));
            System.out.println("Wrote " + result.output());
            return 0;
        } catch (InvalidPageRangeException e) {
            System.err.println("Invalid page range: " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        } catch (PdfOperationException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    private static TextFormat parseFormat(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "txt", "text" -> TextFormat.TXT;
            case "docx", "word" -> TextFormat.DOCX;
            default -> throw new IllegalArgumentException("Unknown format '" + s + "' (use txt or docx).");
        };
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
