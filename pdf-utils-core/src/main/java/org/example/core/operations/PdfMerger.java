package org.example.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.*;

import java.io.IOException;
import java.util.List;

public class PdfMerger {

    public static MergeResult execute(MergeOptions opts) throws PdfOperationException {
        try (PDDocument merged = new PDDocument()) {
            int totalPages = 0;
            for (PageSource source : opts.sources()) {
                totalPages += appendSource(merged, source);
            }
            merged.save(opts.output().toFile());
            return new MergeResult(opts.output(), totalPages);
        } catch (IOException e) {
            throw new PdfOperationException("Merge failed: " + e.getMessage(), e);
        }
    }

    private static int appendSource(PDDocument merged, PageSource source) throws IOException {
        return switch (source) {
            case PageSource.PdfPageSource ps -> appendPdf(merged, ps);
            case PageSource.ImageSource is   -> appendImage(merged, is);
        };
    }

    private static int appendPdf(PDDocument merged, PageSource.PdfPageSource source)
            throws IOException {
        try (PDDocument src = Loader.loadPDF(source.file().toFile())) {
            List<Integer> pageNums = source.range().isAll()
                ? allPageNumbers(src.getNumberOfPages())
                : source.range().pageNumbers();
            for (int pageNum : pageNums) {
                merged.importPage(src.getPage(pageNum - 1));
            }
            return pageNums.size();
        }
    }

    private static int appendImage(PDDocument merged, PageSource.ImageSource source)
            throws IOException {
        ImageToPdfConverter.appendImagePage(merged, source.file(), source.targetSize());
        return 1;
    }

    private static List<Integer> allPageNumbers(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count).boxed().toList();
    }
}
