package com.pdfconduit.core.operations;

import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.MetadataOptions;
import com.pdfconduit.core.model.PdfMetadata;
import com.pdfconduit.core.model.PdfResult;
import com.pdfconduit.core.util.OutputPaths;

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
        try (PDDocument doc = PdfLoader.load(pdf)) {
            return readFrom(doc);
        } catch (IOException e) {
            throw new PdfOperationException("Could not read metadata: " + e.getMessage(), e);
        }
    }

    /** In-memory variant: the title/author/subject/keywords of the PDF {@code pdf}. */
    public static PdfMetadata readBytes(byte[] pdf) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            return readFrom(doc);
        } catch (IOException e) {
            throw new PdfOperationException("Could not read metadata: " + e.getMessage(), e);
        }
    }

    public static PdfResult execute(MetadataOptions opts) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(opts.input())) {
            applyMetadata(doc, opts.title(), opts.author(), opts.subject(),
                opts.keywords(), opts.strip());
            OutputPaths.ensureParentDir(opts.output());
            doc.save(opts.output().toFile());
            return new PdfResult(opts.output(), doc.getNumberOfPages());
        } catch (IOException e) {
            throw new PdfOperationException("Could not write metadata: " + e.getMessage(), e);
        }
    }

    /**
     * In-memory variant: edit (or, when {@code strip}, clear) the metadata of {@code pdf}
     * and return the new PDF bytes. A {@code null} field is left unchanged; a blank field
     * clears that entry.
     */
    public static byte[] executeBytes(byte[] pdf, String title, String author, String subject,
                                      String keywords, boolean strip) throws PdfOperationException {
        try (PDDocument doc = PdfLoader.load(pdf)) {
            applyMetadata(doc, title, author, subject, keywords, strip);
            return PdfLoader.toBytes(doc);
        } catch (IOException e) {
            throw new PdfOperationException("Could not write metadata: " + e.getMessage(), e);
        }
    }

    private static PdfMetadata readFrom(PDDocument doc) {
        PDDocumentInformation info = doc.getDocumentInformation();
        return new PdfMetadata(info.getTitle(), info.getAuthor(),
            info.getSubject(), info.getKeywords());
    }

    /** The shared algorithm: apply metadata edits (or a full strip) to {@code doc}. */
    static void applyMetadata(PDDocument doc, String title, String author, String subject,
                              String keywords, boolean strip) {
        if (strip) {
            doc.setDocumentInformation(new PDDocumentInformation());
            doc.getDocumentCatalog().setMetadata(null);   // drop XMP too
        } else {
            PDDocumentInformation info = doc.getDocumentInformation();
            if (title != null)    info.setTitle(emptyToNull(title));
            if (author != null)   info.setAuthor(emptyToNull(author));
            if (subject != null)  info.setSubject(emptyToNull(subject));
            if (keywords != null) info.setKeywords(emptyToNull(keywords));
        }
    }

    private static String emptyToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
