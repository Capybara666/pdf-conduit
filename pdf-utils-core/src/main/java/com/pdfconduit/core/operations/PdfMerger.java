package com.pdfconduit.core.operations;

import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.*;
import com.pdfconduit.core.util.OutputPaths;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public final class PdfMerger {

    private PdfMerger() {}

    public static MergeResult execute(MergeOptions opts) throws PdfOperationException {
        List<PDDocument> srcDocs = new ArrayList<>();
        try {
            PDDocument merged = new PDDocument();
            int totalPages = 0;
            for (PageSource source : opts.sources()) {
                totalPages += appendSource(merged, source, srcDocs);
            }
            // importPage() keeps live references into source file handles.
            // Sources must stay open until after save() completes.
            OutputPaths.ensureParentDir(opts.output());
            merged.save(opts.output().toFile());
            merged.close();
            return new MergeResult(opts.output(), totalPages);
        } catch (IOException e) {
            throw new PdfOperationException("Merge failed: " + e.getMessage(), e);
        } finally {
            for (PDDocument src : srcDocs) {
                try { src.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static int appendSource(PDDocument merged, PageSource source,
                                    List<PDDocument> srcDocs) throws IOException, PdfOperationException {
        return switch (source) {
            case PageSource.PdfPageSource ps -> appendPdf(merged, ps, srcDocs);
            case PageSource.ImageSource is   -> appendImage(merged, is);
        };
    }

    /**
     * In-memory variant: merge every page of each PDF in {@code pdfs} (in order) into
     * one PDF and return its bytes. Non-PDF inputs must be converted to PDF bytes by the
     * caller first (see {@code MemoryOperations}); this method only accepts PDF bytes.
     */
    public static byte[] executeBytes(List<byte[]> pdfs) throws PdfOperationException {
        List<PDDocument> srcDocs = new ArrayList<>();
        try {
            PDDocument merged = new PDDocument();
            for (byte[] pdf : pdfs) {
                PDDocument src = PdfLoader.load(pdf);
                srcDocs.add(src);
                importPages(merged, src, allPageNumbers(src.getNumberOfPages()));
            }
            // Sources must stay open until save() completes (importPage keeps live refs).
            byte[] out = PdfLoader.toBytes(merged);
            merged.close();
            return out;
        } catch (IOException e) {
            throw new PdfOperationException("Merge failed: " + e.getMessage(), e);
        } finally {
            for (PDDocument src : srcDocs) {
                try { src.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static int appendPdf(PDDocument merged, PageSource.PdfPageSource source,
                                  List<PDDocument> srcDocs) throws IOException, PdfOperationException {
        PDDocument src = PdfLoader.load(source.file());
        srcDocs.add(src);
        List<Integer> pageNums = source.range().isAll()
            ? allPageNumbers(src.getNumberOfPages())
            : source.range().pageNumbers();
        importPages(merged, src, pageNums);
        return pageNums.size();
    }

    /** Imports the given 1-based pages of {@code src} into {@code merged}. */
    private static void importPages(PDDocument merged, PDDocument src, List<Integer> pageNums)
            throws IOException {
        for (int pageNum : pageNums) {
            merged.importPage(src.getPage(pageNum - 1));
        }
    }

    private static int appendImage(PDDocument merged, PageSource.ImageSource source)
            throws IOException {
        ImageToPdfConverter.appendImagePage(merged, source.file(), source.targetSize());
        return 1;
    }

    private static List<Integer> allPageNumbers(int count) {
        return IntStream.rangeClosed(1, count).boxed().toList();
    }
}
