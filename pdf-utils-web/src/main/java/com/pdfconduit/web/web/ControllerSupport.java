package com.pdfconduit.web.web;

import com.pdfconduit.web.support.TempWorkspace;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Shared helpers for the REST controllers: upload guards, saving, filename derivation. */
final class ControllerSupport {

    private ControllerSupport() {}

    /** Rejects an empty upload or one exceeding the per-request file guardrail (→ 400). */
    static void guardCount(List<MultipartFile> files, int max) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("No files were uploaded.");
        }
        if (files.size() > max) {
            throw new IllegalArgumentException(
                "Too many files: " + files.size() + " (limit " + max + " per request).");
        }
    }

    /** Saves every uploaded part into {@code ws}, preserving order. */
    static List<Path> saveAll(TempWorkspace ws, List<MultipartFile> files) throws IOException {
        List<Path> paths = new ArrayList<>(files.size());
        for (MultipartFile f : files) paths.add(ws.save(f));
        return paths;
    }

    /** The upload's original name without extension (for output base names). */
    static String stem(Path saved) {
        String name = saved.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Ensures a chosen output name ends in {@code .pdf}. */
    static String ensurePdf(String name) {
        String n = name.strip();
        return n.toLowerCase().endsWith(".pdf") ? n : n + ".pdf";
    }
}
