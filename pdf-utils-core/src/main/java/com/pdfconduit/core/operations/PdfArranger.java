package com.pdfconduit.core.operations;

import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.ArrangeOptions;
import com.pdfconduit.core.model.ArrangeResult;
import com.pdfconduit.core.util.OutputPaths;

import java.io.IOException;
import java.util.List;

/**
 * Writes a new PDF whose pages follow {@code opts.order()} — a list of 1-indexed
 * page numbers taken from the source. The list preserves order, may repeat a page
 * (duplicating it) and may omit pages (dropping them). An empty order copies the
 * document unchanged. Stateless and thread-safe.
 */
public final class PdfArranger {

    private PdfArranger() {}

    public static ArrangeResult execute(ArrangeOptions opts) throws PdfOperationException {
        try (PDDocument src = PdfLoader.load(opts.input());
             PDDocument out = arrangeDoc(src, opts.order())) {
            OutputPaths.ensureParentDir(opts.output());
            out.save(opts.output().toFile());
            return new ArrangeResult(opts.output(), out.getNumberOfPages());
        } catch (IOException e) {
            throw new PdfOperationException("Arrange failed: " + e.getMessage(), e);
        }
    }

    /** In-memory variant: re-order the PDF {@code pdf} by {@code order} and return the new PDF bytes. */
    public static byte[] executeBytes(byte[] pdf, List<Integer> order) throws PdfOperationException {
        try (PDDocument src = PdfLoader.load(pdf);
             PDDocument out = arrangeDoc(src, order)) {
            return PdfLoader.toBytes(out);
        } catch (IOException e) {
            throw new PdfOperationException("Arrange failed: " + e.getMessage(), e);
        }
    }

    /** The shared algorithm: build a new document whose pages follow {@code order}. */
    static PDDocument arrangeDoc(PDDocument src, List<Integer> order)
            throws PdfOperationException, IOException {
        PDDocument out = new PDDocument();
        int total = src.getNumberOfPages();
        if (order.isEmpty()) {
            for (int i = 1; i <= total; i++) out.importPage(src.getPage(i - 1));
        } else {
            for (int pageNum : order) {
                if (pageNum < 1 || pageNum > total) {
                    out.close();
                    throw new PdfOperationException(
                        "Page " + pageNum + " is out of range (document has " + total + " pages).");
                }
                out.importPage(src.getPage(pageNum - 1));
            }
        }
        return out;
    }
}
