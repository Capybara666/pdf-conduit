package com.pdfconduit.core.pipeline;

import com.pdfconduit.core.service.Cardinality;
import com.pdfconduit.core.service.OperationType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NodeKindCatalogTest {

    @Test
    void everyOperationNodeMapsToATypeWithMatchingSuffixAndCardinality() {
        for (NodeKind k : NodeKind.values()) {
            if (k == NodeKind.SOURCE) {
                assertNull(k.operationType());
                assertEquals("", k.suffix());
                continue;
            }
            OperationType t = k.operationType();
            assertNotNull(t, k + " has no OperationType");
            assertEquals(t.suffix(), k.suffix(), k + " suffix");
            assertEquals(t.cardinality() == Cardinality.REDUCE, k.isReduce(), k + " isReduce");
            assertEquals(t.cardinality() == Cardinality.MAP, k.isMap(), k + " isMap");
        }
    }
}
