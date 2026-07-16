package com.pdfconduit.web.support;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.PageSource;
import com.pdfconduit.core.service.InputSources;
import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.nio.file.Path;
import java.util.List;

/**
 * Input routing for the web layer. The actual type-routing logic is the shared core
 * {@link InputSources} (the same code the CLI uses) — this class is a thin,
 * web-named entry point plus a page-count helper for range parsing.
 */
public final class Uploads {

    private Uploads() {}

    /** Builds merge {@link PageSource}s, converting office/image inputs (temps collected). */
    public static List<PageSource> toPageSources(List<Path> inputs, PageSize imageSize, List<Path> temps)
            throws PdfOperationException {
        return InputSources.build(inputs, imageSize, temps);
    }

    /** Best-effort deletion of temp PDFs produced by {@link #toPageSources}. */
    public static void deleteTemps(List<Path> temps) {
        InputSources.deleteTemps(temps);
    }

    /** Page count of a PDF, for parsing page-range / page-order expressions. */
    public static int countPages(Path pdf) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            return doc.getNumberOfPages();
        } catch (java.io.IOException e) {
            throw new PdfOperationException("Cannot read PDF: " + e.getMessage(), e);
        }
    }
}
