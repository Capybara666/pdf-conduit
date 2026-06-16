package org.example.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.RotateOptions;
import org.example.core.model.RotateResult;
import org.example.core.util.OutputPaths;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class PdfRotator {

    public static RotateResult execute(RotateOptions opts) throws PdfOperationException {
        try (PDDocument doc = Loader.loadPDF(opts.input().toFile())) {
            int total = doc.getNumberOfPages();
            Set<Integer> targets = opts.pages().isAll()
                ? IntStream.rangeClosed(1, total).boxed().collect(Collectors.toSet())
                : Set.copyOf(opts.pages().pageNumbers());

            int rotated = 0;
            for (int i = 1; i <= total; i++) {
                if (targets.contains(i)) {
                    PDPage page = doc.getPage(i - 1);
                    page.setRotation((page.getRotation() + opts.angleDegrees()) % 360);
                    rotated++;
                }
            }
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new RotateResult(opts.output(), rotated);

        } catch (IOException e) {
            throw new PdfOperationException("Rotate failed: " + e.getMessage(), e);
        }
    }
}
