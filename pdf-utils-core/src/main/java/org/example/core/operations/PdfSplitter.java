package org.example.core.operations;

import org.example.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.SplitMode;
import org.example.core.model.SplitOptions;
import org.example.core.model.SplitResult;
import org.example.core.util.OutputPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public final class PdfSplitter {

    private PdfSplitter() {}

    public static SplitResult execute(SplitOptions opts) throws PdfOperationException {
        try (PDDocument src = PdfLoader.load(opts.input())) {
            int total = src.getNumberOfPages();
            List<Integer> pageNums = opts.pages().isAll() ? allPages(total) : opts.pages().pageNumbers();

            return opts.mode() == SplitMode.SEPARATE
                ? separate(src, pageNums, total, opts.output(), stem(opts.input()))
                : combine(src, pageNums, opts.output());

        } catch (IOException e) {
            throw new PdfOperationException("Split failed: " + e.getMessage(), e);
        }
    }

    /** Selected pages → one PDF at {@code outputFile}. */
    private static SplitResult combine(PDDocument src, List<Integer> pageNums, Path outputFile)
            throws IOException {
        try (PDDocument out = new PDDocument()) {
            for (int pageNum : pageNums) out.importPage(src.getPage(pageNum - 1));
            OutputPaths.ensureParentDir(outputFile);
            out.save(outputFile.toFile());
            return new SplitResult(List.of(outputFile), out.getNumberOfPages());
        }
    }

    /** Each selected page → its own PDF inside {@code outputDir}. */
    private static SplitResult separate(PDDocument src, List<Integer> pageNums, int total,
                                        Path outputDir, String stem) throws IOException {
        Files.createDirectories(outputDir);
        int width = Integer.toString(total).length();
        List<Path> outputs = new ArrayList<>(pageNums.size());
        for (int pageNum : pageNums) {
            Path out = outputDir.resolve(stem + "_p" + pad(pageNum, width) + ".pdf");
            try (PDDocument one = new PDDocument()) {
                one.importPage(src.getPage(pageNum - 1));
                one.save(out.toFile());
            }
            outputs.add(out);
        }
        return new SplitResult(outputs, outputs.size());
    }

    private static List<Integer> allPages(int count) {
        return IntStream.rangeClosed(1, count).boxed().toList();
    }

    /** The input's file name without extension, used to name per-page outputs. */
    private static String stem(Path input) {
        String name = input.getFileName() != null ? input.getFileName().toString() : "page";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String pad(int n, int width) {
        return String.format("%0" + Math.max(1, width) + "d", n);
    }
}
