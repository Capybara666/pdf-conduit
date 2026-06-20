package org.example.app.cli;

import org.example.core.convert.DocumentConverter;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.PageRange;
import org.example.core.model.PageSize;
import org.example.core.model.PageSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds merge sources from CLI input paths, routing each file by type so the CLI
 * accepts the same inputs as the GUI:
 *
 * <ul>
 *   <li>PDFs become page sources,</li>
 *   <li>images are placed at {@code imageSize},</li>
 *   <li>office/text documents are converted to a temp PDF (via a headless
 *       LibreOffice) and added to {@code temps} for the caller to delete.</li>
 * </ul>
 *
 * Throws {@link PdfOperationException} for an unsupported file type.
 */
public final class CliSources {

    private CliSources() {}

    public static List<PageSource> build(List<Path> inputs, PageSize imageSize, List<Path> temps)
            throws PdfOperationException {
        List<PageSource> sources = new ArrayList<>();
        for (Path p : inputs) {
            switch (DocumentConverter.classify(p)) {
                case PDF -> sources.add(new PageSource.PdfPageSource(p, PageRange.ALL));
                case IMAGE -> sources.add(new PageSource.ImageSource(p, imageSize));
                case OFFICE -> {
                    Path pdf = DocumentConverter.ensurePdf(p, PageSize.FIT, temps);
                    sources.add(new PageSource.PdfPageSource(pdf, PageRange.ALL));
                }
                case UNSUPPORTED -> throw new PdfOperationException(
                    "Unsupported file type: " + p.getFileName());
            }
        }
        return sources;
    }

    /** Best-effort deletion of the temp PDFs produced by {@link #build}. */
    public static void deleteTemps(List<Path> temps) {
        for (Path t : temps) {
            try { Files.deleteIfExists(t); } catch (IOException ignored) {}
        }
    }
}
