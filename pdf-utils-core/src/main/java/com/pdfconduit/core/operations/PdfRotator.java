package com.pdfconduit.core.operations;

import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageRange;
import com.pdfconduit.core.model.RotateOptions;
import com.pdfconduit.core.model.RotateResult;
import com.pdfconduit.core.util.OutputPaths;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class PdfRotator {

    private PdfRotator() {}

    public static RotateResult execute(RotateOptions opts) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(opts.input())) {
            int rotated = applyRotation(doc, opts.pages(), opts.angleDegrees());
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new RotateResult(opts.output(), rotated);

        } catch (IOException e) {
            throw new PdfOperationException("Rotate failed: " + e.getMessage(), e);
        }
    }

    /** In-memory variant: rotate {@code pages} of the PDF {@code pdf} and return the new PDF bytes. */
    public static byte[] executeBytes(byte[] pdf, PageRange pages, int angleDegrees)
            throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            applyRotation(doc, pages, angleDegrees);
            return PdfLoader.toBytes(doc);
        } catch (IOException e) {
            throw new PdfOperationException("Rotate failed: " + e.getMessage(), e);
        }
    }

    /** The shared {@code PDDocument}-level algorithm: rotate the selected pages, returning the count. */
    static int applyRotation(PDDocument doc, PageRange pages, int angleDegrees) {
        int total = doc.getNumberOfPages();
        Set<Integer> targets = pages.isAll()
            ? IntStream.rangeClosed(1, total).boxed().collect(Collectors.toSet())
            : Set.copyOf(pages.pageNumbers());

        int rotated = 0;
        for (int i = 1; i <= total; i++) {
            if (targets.contains(i)) {
                PDPage page = doc.getPage(i - 1);
                page.setRotation((page.getRotation() + angleDegrees) % 360);
                rotated++;
            }
        }
        return rotated;
    }
}
