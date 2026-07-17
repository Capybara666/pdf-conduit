package com.pdfconduit.app.cli;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.PageSource;
import com.pdfconduit.core.service.InputSources;

import java.nio.file.Path;
import java.util.List;

/**
 * Builds merge sources from CLI input paths. The routing logic now lives in the
 * shared core {@link InputSources} (so desktop and web share one implementation);
 * this class remains as the CLI's thin, stable entry point and delegates.
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
        return InputSources.build(inputs, imageSize, temps);
    }

    /** Best-effort deletion of the temp PDFs produced by {@link #build}. */
    public static void deleteTemps(List<Path> temps) {
        InputSources.deleteTemps(temps);
    }
}
