package com.pdfconduit.web.web;

import com.pdfconduit.core.service.NamedBytes;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Shared request-validation helpers for the REST controllers. */
final class ControllerSupport {

    private ControllerSupport() {}

    /** Summed byte size of already-read uploads (the load-guard's in-flight estimate). */
    static long totalBytes(List<NamedBytes> inputs) {
        long sum = 0;
        for (NamedBytes in : inputs) sum += in.data().length;
        return sum;
    }

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

    /** Ensures a chosen output name ends in {@code .pdf}. */
    static String ensurePdf(String name) {
        String n = name.strip();
        return n.toLowerCase().endsWith(".pdf") ? n : n + ".pdf";
    }
}
