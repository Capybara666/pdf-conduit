package org.example.core.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PipelineStoreTest {

    @TempDir Path tmp;

    @Test
    void savesAndLoadsAPipeline() throws Exception {
        PipelineModel m = new PipelineModel();
        PipelineNode src = new PipelineNode("s", NodeKind.SOURCE, 10, 20);
        src.files.add(Path.of("/tmp/a.pdf"));
        PipelineNode rot = new PipelineNode("r", NodeKind.ROTATE, 100, 50);
        rot.angle = 180;
        rot.pages = "1-3";
        rot.outputDestination = "/tmp/out.pdf";
        m.nodes.add(src);
        m.nodes.add(rot);
        m.connections.add(new Connection("s", "r"));

        Path file = tmp.resolve("pipe.json");
        PipelineStore.save(m, file);
        PipelineModel loaded = PipelineStore.load(file);

        assertEquals(2, loaded.nodes.size());
        assertEquals(1, loaded.connections.size());

        PipelineNode lr = loaded.node("r");
        assertNotNull(lr);
        assertEquals(NodeKind.ROTATE, lr.kind);
        assertEquals(180, lr.angle);
        assertEquals("1-3", lr.pages);
        assertEquals("/tmp/out.pdf", lr.outputDestination);
        assertEquals(100.0, lr.x);

        PipelineNode ls = loaded.node("s");
        assertEquals(1, ls.files.size());
        assertEquals(Path.of("/tmp/a.pdf"), ls.files.get(0));

        assertEquals("s", loaded.connections.get(0).fromNodeId());
        assertEquals("r", loaded.connections.get(0).toNodeId());
    }

    @Test
    void loadRejectsAnEmptyFile() throws Exception {
        Path file = tmp.resolve("empty.json");
        java.nio.file.Files.writeString(file, "");
        assertThrows(java.io.IOException.class, () -> PipelineStore.load(file));
    }
}
