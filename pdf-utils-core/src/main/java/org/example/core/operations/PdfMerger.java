package org.example.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.*;
import org.example.core.util.OutputPaths;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PdfMerger {

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
                                    List<PDDocument> srcDocs) throws IOException {
        return switch (source) {
            case PageSource.PdfPageSource ps -> appendPdf(merged, ps, srcDocs);
            case PageSource.ImageSource is   -> appendImage(merged, is);
        };
    }

    private static int appendPdf(PDDocument merged, PageSource.PdfPageSource source,
                                  List<PDDocument> srcDocs) throws IOException {
        PDDocument src = Loader.loadPDF(source.file().toFile());
        srcDocs.add(src);
        List<Integer> pageNums = source.range().isAll()
            ? allPageNumbers(src.getNumberOfPages())
            : source.range().pageNumbers();
        for (int pageNum : pageNums) {
            merged.importPage(src.getPage(pageNum - 1));
        }
        return pageNums.size();
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
