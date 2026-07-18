package com.pdfconduit.web.support;

import com.pdfconduit.core.service.NamedBytes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zip-slip guard (S8): crafted entry names are reduced to safe basenames. */
class ZipsTest {

    @Test
    void sanitize_stripsPathsAndTraversal() {
        assertEquals("evil.pdf", Zips.sanitize("../../etc/evil.pdf"));
        assertEquals("evil.pdf", Zips.sanitize("..\\..\\windows\\evil.pdf"));
        assertEquals("doc.pdf", Zips.sanitize("/abs/path/doc.pdf"));
        assertEquals("file", Zips.sanitize(".."));
        assertEquals("file", Zips.sanitize(""));
        assertEquals("file", Zips.sanitize(null));
        assertEquals("plain.pdf", Zips.sanitize("plain.pdf"));
    }

    @Test
    void zip_entriesHaveNoPathSeparatorsOrTraversal() throws IOException {
        byte[] archive = Zips.zip(List.of(
            new NamedBytes("../../../etc/passwd", new byte[]{1, 2, 3}),
            new NamedBytes("sub\\dir\\a.pdf", new byte[]{4, 5, 6})));

        List<String> names = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry e; (e = zis.getNextEntry()) != null; ) names.add(e.getName());
        }

        assertFalse(names.isEmpty());
        for (String name : names) {
            assertFalse(name.contains("/"), name);
            assertFalse(name.contains("\\"), name);
            assertFalse(name.contains(".."), name);
        }
        assertTrue(names.contains("passwd"), names.toString());
        assertTrue(names.contains("a.pdf"), names.toString());
    }

    /** P7: batch inputs sharing a basename must not overwrite each other in the archive. */
    @Test
    void zip_deduplicatesCollidingBasenames() throws IOException {
        byte[] archive = Zips.zip(List.of(
            new NamedBytes("report.pdf", new byte[]{1}),
            new NamedBytes("a/report.pdf", new byte[]{2}),
            new NamedBytes("b/report.pdf", new byte[]{3})));

        List<String> names = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry e; (e = zis.getNextEntry()) != null; ) names.add(e.getName());
        }

        // Three inputs collide on "report.pdf" → three distinct entries, none lost.
        assertEquals(3, names.size(), names.toString());
        assertEquals(3, names.stream().distinct().count(), names.toString());
        assertTrue(names.contains("report.pdf"), names.toString());
        assertTrue(names.contains("report_2.pdf"), names.toString());
        assertTrue(names.contains("report_3.pdf"), names.toString());
    }
}
