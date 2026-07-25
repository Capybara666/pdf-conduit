package com.pdfconduit.web.support;

import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.service.MemoryOperations;
import com.pdfconduit.core.service.NamedBytes;
import com.pdfconduit.core.util.Filenames;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.error.OfficeDisabledException;
import com.pdfconduit.web.guard.OfficeGuard;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory input routing for the web layer: reads uploaded parts into {@link NamedBytes}
 * (filename + bytes) and routes them to PDF bytes via the shared core
 * {@link MemoryOperations#toPdfBytes} (PDF passthrough, image → in-memory Image-to-PDF,
 * office → the documented temp-dir exception). Never writes to disk itself.
 *
 * <p>When {@code pdfconduit.web.office.enabled=false} an office/document upload is rejected up
 * front (415) before any conversion is attempted, keeping the request fully in-memory.
 */
@Component
public class Uploads {

    private final boolean officeEnabled;
    private final OfficeGuard officeGuard;

    public Uploads(WebProperties props, OfficeGuard officeGuard) {
        this.officeEnabled = props.officeEnabled();
        this.officeGuard = officeGuard;
    }

    /** Reads a part into {@link NamedBytes}, rejecting office uploads when office is disabled. */
    public NamedBytes read(MultipartFile file) throws IOException {
        String name = filename(file);
        guardOffice(name);
        return new NamedBytes(name, file.getBytes());
    }

    /** Reads every part, preserving order. */
    public List<NamedBytes> readAll(List<MultipartFile> files) throws IOException {
        List<NamedBytes> out = new ArrayList<>(files.size());
        for (MultipartFile f : files) out.add(read(f));
        return out;
    }

    /** Reads a part and routes it to PDF bytes (image/office converted; office conversions gated). */
    public byte[] toPdf(MultipartFile file) throws IOException, PdfOperationException {
        return toPdf(read(file));
    }

    /** Routes an already-read upload to PDF bytes (image/office converted; office conversions gated). */
    public byte[] toPdf(NamedBytes raw) throws PdfOperationException {
        guardOffice(raw.filename());
        try {
            return officeGuard.run(raw.filename(),
                () -> MemoryOperations.toPdfBytes(raw.data(), raw.filename()));
        } catch (IOException e) {
            throw new PdfOperationException("Cannot convert upload: " + e.getMessage(), e);
        }
    }

    /** Rejects an office/document upload when office conversion is disabled. */
    public void guardOffice(String filename) {
        if (!officeEnabled
                && DocumentConverter.classify(Path.of(filename)) == DocumentConverter.Kind.OFFICE) {
            throw new OfficeDisabledException(filename);
        }
    }

    /** The upload's original basename (path stripped), falling back to {@code upload}. */
    public static String filename(MultipartFile file) {
        return Filenames.basename(file.getOriginalFilename(), "upload");
    }
}
