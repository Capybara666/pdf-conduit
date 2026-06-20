package org.example.core.operations;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.example.core.exception.PdfOperationException;
import org.example.core.model.MetadataOptions;
import org.example.core.model.PdfMetadata;
import org.example.core.model.PdfResult;
import org.example.core.util.OutputPaths;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads and edits a PDF's document-information metadata (title, author, subject,
 * keywords). Stateless and thread-safe.
 */
public final class PdfMetadataEditor {

    private PdfMetadataEditor() {}

    /** The current title/author/subject/keywords of {@code pdf} (fields may be {@code null}). */
    public static PdfMetadata read(Path pdf) throws PdfOperationException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDDocumentInformation info = doc.getDocumentInformation();
            return new PdfMetadata(info.getTitle(), info.getAuthor(),
                info.getSubject(), info.getKeywords());
        } catch (IOException e) {
            throw new PdfOperationException("Could not read metadata: " + e.getMessage(), e);
        }
    }

    public static PdfResult execute(MetadataOptions opts) throws PdfOperationException {
        try (PDDocument doc = Loader.loadPDF(opts.input().toFile())) {
            if (opts.strip()) {
                doc.setDocumentInformation(new PDDocumentInformation());
                doc.getDocumentCatalog().setMetadata(null);   // drop XMP too
            } else {
                PDDocumentInformation info = doc.getDocumentInformation();
                if (opts.title() != null)    info.setTitle(emptyToNull(opts.title()));
                if (opts.author() != null)   info.setAuthor(emptyToNull(opts.author()));
                if (opts.subject() != null)  info.setSubject(emptyToNull(opts.subject()));
                if (opts.keywords() != null) info.setKeywords(emptyToNull(opts.keywords()));
            }
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new PdfResult(opts.output(), doc.getNumberOfPages());
        } catch (IOException e) {
            throw new PdfOperationException("Could not write metadata: " + e.getMessage(), e);
        }
    }

    private static String emptyToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
