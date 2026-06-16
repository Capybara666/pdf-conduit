package org.example.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.SplitOptions;
import org.example.core.model.SplitResult;

import java.io.IOException;
import java.util.List;

public class PdfSplitter {

    public static SplitResult execute(SplitOptions opts) throws PdfOperationException {
        try (PDDocument src = Loader.loadPDF(opts.input().toFile());
             PDDocument out = new PDDocument()) {

            int total = src.getNumberOfPages();
            List<Integer> pageNums = opts.pages().isAll()
                ? allPages(total)
                : opts.pages().pageNumbers();

            for (int pageNum : pageNums) {
                out.importPage(src.getPage(pageNum - 1));
            }
            out.save(opts.output().toFile());
            return new SplitResult(opts.output(), out.getNumberOfPages());

        } catch (IOException e) {
            throw new PdfOperationException("Split failed: " + e.getMessage(), e);
        }
    }

    private static List<Integer> allPages(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count).boxed().toList();
    }
}
