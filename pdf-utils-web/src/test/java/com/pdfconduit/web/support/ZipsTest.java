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
}
