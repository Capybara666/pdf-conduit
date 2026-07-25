package com.pdfconduit.core.operations;

import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.SplitMode;
import com.pdfconduit.core.model.SplitOptions;
import com.pdfconduit.core.model.SplitResult;
import com.pdfconduit.core.util.OutputPaths;

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

    /**
     * In-memory COMBINE variant: selected pages of {@code pdf} → one PDF's bytes.
     * {@code pages} may be {@link com.pdfconduit.core.model.PageRange#ALL}.
     */
    public static byte[] combineBytes(byte[] pdf, com.pdfconduit.core.model.PageRange pages)
            throws PdfOperationException {
        try (PDDocument src = PdfLoader.load(pdf)) {
            List<Integer> pageNums = pages.isAll() ? allPages(src.getNumberOfPages()) : pages.pageNumbers();
            try (PDDocument out = combineDoc(src, pageNums)) {
                return PdfLoader.toBytes(out);
            }
        } catch (IOException e) {
            throw new PdfOperationException("Split failed: " + e.getMessage(), e);
        }
    }

    /**
     * In-memory SEPARATE variant: each selected page of {@code pdf} → its own PDF's bytes,
     * in page order.
     */
    public static List<byte[]> separateBytes(byte[] pdf, com.pdfconduit.core.model.PageRange pages)
            throws PdfOperationException {
        return separateBytes(pdf, pages, null);
    }

    /**
     * As {@link #separateBytes(byte[], com.pdfconduit.core.model.PageRange)}, but reporting the
     * accumulated output size to {@code outputGuard} after every page so a caller with a memory
     * budget (the web backend) can abort a pathological split early instead of holding thousands
     * of single-page PDFs in the heap. {@code null} ⇒ unbounded (the desktop/CLI default).
     */
    public static List<byte[]> separateBytes(byte[] pdf, com.pdfconduit.core.model.PageRange pages,
                                             com.pdfconduit.core.service.OutputSizeGuard outputGuard)
            throws PdfOperationException {
        try (PDDocument src = PdfLoader.load(pdf)) {
            List<Integer> pageNums = pages.isAll() ? allPages(src.getNumberOfPages()) : pages.pageNumbers();
            List<byte[]> outputs = new ArrayList<>(pageNums.size());
            long accumulated = 0;
            for (int pageNum : pageNums) {
                try (PDDocument one = singlePageDoc(src, pageNum)) {
                    byte[] part = PdfLoader.toBytes(one);
                    outputs.add(part);
                    accumulated += part.length;
                }
                if (outputGuard != null) outputGuard.check(accumulated);
            }
            return outputs;
        } catch (IOException e) {
            throw new PdfOperationException("Split failed: " + e.getMessage(), e);
        }
    }

    /** Selected pages → one PDF at {@code outputFile}. */
    private static SplitResult combine(PDDocument src, List<Integer> pageNums, Path outputFile)
            throws IOException {
        try (PDDocument out = combineDoc(src, pageNums)) {
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
            try (PDDocument one = singlePageDoc(src, pageNum)) {
                one.save(out.toFile());
            }
            outputs.add(out);
        }
        return new SplitResult(outputs, outputs.size());
    }

    /** A new document holding the given 1-based pages of {@code src}, in order. */
    private static PDDocument combineDoc(PDDocument src, List<Integer> pageNums) throws IOException {
        PDDocument out = new PDDocument();
        for (int pageNum : pageNums) out.importPage(src.getPage(pageNum - 1));
        return out;
    }

    /** A new one-page document holding page {@code pageNum} (1-based) of {@code src}. */
    private static PDDocument singlePageDoc(PDDocument src, int pageNum) throws IOException {
        PDDocument one = new PDDocument();
        one.importPage(src.getPage(pageNum - 1));
        return one;
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
