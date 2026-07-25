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
                ? separate(src, pageNums, total, opts.output(), stem(opts.input()), opts.pagesPerChunk())
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
        return separateBytes(pdf, pages, 1);
    }

    /**
     * In-memory "split every N pages" variant: the selected pages of {@code pdf}, cut into
     * consecutive groups of {@code pagesPerChunk} in page order, each group → its own PDF's bytes.
     * The last group may be shorter; {@code pagesPerChunk} at or above the number of selected pages
     * yields a single output. {@code pagesPerChunk == 1} is exactly
     * {@link #separateBytes(byte[], com.pdfconduit.core.model.PageRange)}.
     */
    public static List<byte[]> separateBytes(byte[] pdf, com.pdfconduit.core.model.PageRange pages,
                                             int pagesPerChunk) throws PdfOperationException {
        requireChunk(pagesPerChunk);
        try (PDDocument src = PdfLoader.load(pdf)) {
            List<Integer> pageNums = pages.isAll() ? allPages(src.getNumberOfPages()) : pages.pageNumbers();
            List<List<Integer>> chunks = chunk(pageNums, pagesPerChunk);
            List<byte[]> outputs = new ArrayList<>(chunks.size());
            for (List<Integer> group : chunks) {
                try (PDDocument part = combineDoc(src, group)) {
                    outputs.add(PdfLoader.toBytes(part));
                }
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

    /**
     * Each group of {@code pagesPerChunk} selected pages → its own PDF inside {@code outputDir}.
     * A one-page group keeps the historic {@code <stem>_p07.pdf} name; a longer one is named after
     * the pages it spans, {@code <stem>_p07-12.pdf}.
     */
    private static SplitResult separate(PDDocument src, List<Integer> pageNums, int total,
                                        Path outputDir, String stem, int pagesPerChunk)
            throws IOException {
        Files.createDirectories(outputDir);
        int width = Integer.toString(total).length();
        List<Path> outputs = new ArrayList<>();
        int pageCount = 0;
        for (List<Integer> group : chunk(pageNums, pagesPerChunk)) {
            Path out = outputDir.resolve(stem + "_p" + partName(group, width) + ".pdf");
            try (PDDocument part = combineDoc(src, group)) {
                part.save(out.toFile());
            }
            outputs.add(out);
            pageCount += group.size();
        }
        return new SplitResult(outputs, pageCount);
    }

    /** {@code 07} for a single page, {@code 07-12} for a group spanning several. */
    private static String partName(List<Integer> group, int width) {
        int first = group.get(0);
        int last = group.get(group.size() - 1);
        return first == last ? pad(first, width) : pad(first, width) + "-" + pad(last, width);
    }

    /**
     * Cuts {@code pageNums} into consecutive groups of at most {@code size}, preserving order.
     * The last group holds the remainder; a {@code size} at or above the list length gives one group.
     */
    private static List<List<Integer>> chunk(List<Integer> pageNums, int size) {
        List<List<Integer>> chunks = new ArrayList<>();
        for (int i = 0; i < pageNums.size(); i += size) {
            chunks.add(List.copyOf(pageNums.subList(i, Math.min(i + size, pageNums.size()))));
        }
        return chunks;
    }

    private static void requireChunk(int pagesPerChunk) {
        if (pagesPerChunk < 1) {
            throw new IllegalArgumentException("Pages per file must be at least 1.");
        }
    }

    /** A new document holding the given 1-based pages of {@code src}, in order. */
    private static PDDocument combineDoc(PDDocument src, List<Integer> pageNums) throws IOException {
        PDDocument out = new PDDocument();
        for (int pageNum : pageNums) out.importPage(src.getPage(pageNum - 1));
        return out;
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
