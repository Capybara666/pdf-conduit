package com.pdfconduit.core.service;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class OperationTypeTest {

    @Test
    void everyTypeHasUniqueNonBlankIdAndSuffix() {
        Set<String> ids = new HashSet<>();
        Set<String> suffixes = new HashSet<>();
        for (OperationType t : OperationType.values()) {
            assertFalse(t.id().isBlank(), t + " has blank id");
            assertFalse(t.suffix().isBlank(), t + " has blank suffix");
            assertTrue(t.suffix().startsWith("_"), t + " suffix must start with _");
            assertTrue(ids.add(t.id()), "duplicate id: " + t.id());
            assertTrue(suffixes.add(t.suffix()), "duplicate suffix: " + t.suffix());
        }
    }

    @Test
    void mergeIsTheOnlyReduceAndExtractIsTheOnlyMultiOutput() {
        for (OperationType t : OperationType.values()) {
            assertEquals(t == OperationType.MERGE ? Cardinality.REDUCE : Cardinality.MAP,
                t.cardinality(), t + " cardinality");
            assertEquals(t == OperationType.EXTRACT, t.multiOutput(), t + " multiOutput");
        }
    }

    @Test
    void suffixesMatchTheEstablishedNaming() {
        assertEquals("_compressed", OperationType.COMPRESS.suffix());
        assertEquals("_merged", OperationType.MERGE.suffix());
        assertEquals("_extracted", OperationType.EXTRACT.suffix());
        assertEquals("_converted", OperationType.IMAGES_TO_PDF.suffix());
        assertEquals("_watermarked", OperationType.WATERMARK.suffix());
    }
}
