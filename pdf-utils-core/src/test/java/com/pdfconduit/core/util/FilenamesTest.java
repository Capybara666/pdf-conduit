package com.pdfconduit.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The single filename-hardening rule the upload, ZIP-entry and output-name call sites all share.
 * Traversal, Windows paths and degenerate names must never survive as something a later extraction
 * could act on.
 */
class FilenamesTest {

    @Test
    void basename_stripsPathsAndTraversal() {
        assertEquals("passwd", Filenames.basename("../../etc/passwd"));
        assertEquals("evil.pdf", Filenames.basename("..\\..\\windows\\evil.pdf"));
        assertEquals("doc.pdf", Filenames.basename("/abs/path/doc.pdf"));
        assertEquals("doc.pdf", Filenames.basename("C:\\Users\\me\\doc.pdf"));
        assertEquals("plain.pdf", Filenames.basename("plain.pdf"));
        assertEquals("a.pdf", Filenames.basename("  a.pdf  "));
    }

    @Test
    void basename_fallsBackWhenNothingUsableIsLeft() {
        assertEquals("file", Filenames.basename(""));
        assertEquals("file", Filenames.basename("   "));
        assertEquals("file", Filenames.basename(null));
        assertEquals("file", Filenames.basename("/"));
        // A bare "." / ".." IS a name here — only ZIP entries need it neutralised (below).
        assertEquals(".", Filenames.basename("."));
        assertEquals("..", Filenames.basename(".."));
        // The upload call site keeps its own fallback wording.
        assertEquals("upload", Filenames.basename(null, "upload"));
        assertEquals("upload", Filenames.basename("dir/", "upload"));
    }

    @Test
    void sanitizeEntry_neutralisesTraversalForZipEntries() {
        assertEquals("passwd", Filenames.sanitizeEntry("../../etc/passwd"));
        assertEquals("evil.pdf", Filenames.sanitizeEntry("..\\..\\windows\\evil.pdf"));
        assertEquals("file", Filenames.sanitizeEntry("."));
        assertEquals("file", Filenames.sanitizeEntry(".."));
        assertEquals("file", Filenames.sanitizeEntry("..."));
        assertEquals("file", Filenames.sanitizeEntry(""));
        assertEquals("file", Filenames.sanitizeEntry(null));
        // Normal dotted names keep their dots; only leading ones are dropped.
        assertEquals("my.report.pdf", Filenames.sanitizeEntry("my.report.pdf"));
        assertEquals("env", Filenames.sanitizeEntry(".env"));
    }

    @Test
    void stem_dropsPathAndExtension() {
        assertEquals("report", Filenames.stem("/tmp/report.pdf"));
        assertEquals("report", Filenames.stem("dir\\report.pdf"));
        assertEquals("my.report", Filenames.stem("my.report.pdf"));
        assertEquals("noext", Filenames.stem("noext"));
        assertEquals(".env", Filenames.stem(".env"));
        assertEquals("file", Filenames.stem(""));
        assertEquals("file", Filenames.stem(null));
        assertEquals("file", Filenames.stem("/"));
    }

    @Test
    void nonAsciiNamesSurviveIntact() {
        assertEquals("faktura_żółć.pdf", Filenames.basename("/dane/faktura_żółć.pdf"));
        assertEquals("faktura_żółć", Filenames.stem("/dane/faktura_żółć.pdf"));
        assertEquals("faktura_żółć.pdf", Filenames.sanitizeEntry("../faktura_żółć.pdf"));
        assertEquals("報告書.pdf", Filenames.basename("dir\\報告書.pdf"));
        assertEquals("報告書", Filenames.stem("dir\\報告書.pdf"));
    }
}
