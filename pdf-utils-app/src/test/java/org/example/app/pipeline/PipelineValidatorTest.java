package org.example.app.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipelineValidatorTest {

    private PipelineNode source(String id, String... files) {
        PipelineNode n = new PipelineNode(id, NodeKind.SOURCE, 0, 0);
        for (String f : files) n.files.add(Path.of(f));
        return n;
    }

    @Test
    void detectsCycle() {
        PipelineModel m = new PipelineModel();
        m.nodes.add(new PipelineNode("a", NodeKind.COMPRESS, 0, 0));
        m.nodes.add(new PipelineNode("b", NodeKind.ROTATE, 0, 0));
        m.connections.add(new Connection("a", "b"));
        m.connections.add(new Connection("b", "a"));

        List<ValidationError> errors = PipelineValidator.validate(m);
        assertTrue(errors.stream().anyMatch(e -> e.message().toLowerCase().contains("cycle")));
    }

    @Test
    void acceptsImageIntoCompressViaAutoConversion() {
        PipelineModel m = new PipelineModel();
        m.nodes.add(source("s", "/tmp/photo.png"));
        PipelineNode compress = new PipelineNode("c", NodeKind.COMPRESS, 0, 0);
        compress.outputDestination = "/tmp/out.pdf";
        m.nodes.add(compress);
        m.connections.add(new Connection("s", "c"));

        // Images (and other formats) are converted to PDF automatically, so a
        // non-PDF source feeding any operation is no longer an error.
        assertTrue(PipelineValidator.validate(m).isEmpty());
    }

    @Test
    void rejectsUnsupportedFileType() {
        PipelineModel m = new PipelineModel();
        m.nodes.add(source("s", "/tmp/archive.zip"));
        PipelineNode compress = new PipelineNode("c", NodeKind.COMPRESS, 0, 0);
        compress.outputDestination = "/tmp/out.pdf";
        m.nodes.add(compress);
        m.connections.add(new Connection("s", "c"));

        assertTrue(PipelineValidator.validate(m).stream()
            .anyMatch(e -> "s".equals(e.nodeId()) && e.message().toLowerCase().contains("unsupported")));
    }

    @Test
    void requiresTerminalDestination() {
        PipelineModel m = new PipelineModel();
        m.nodes.add(source("s", "/tmp/a.pdf"));
        m.nodes.add(new PipelineNode("c", NodeKind.COMPRESS, 0, 0)); // no destination
        m.connections.add(new Connection("s", "c"));

        assertTrue(PipelineValidator.validate(m).stream()
            .anyMatch(e -> e.message().toLowerCase().contains("destination")));
    }

    @Test
    void rejectsSeparateSplitThatIsNotTerminal() {
        PipelineModel m = new PipelineModel();
        m.nodes.add(source("s", "/tmp/a.pdf"));
        PipelineNode split = new PipelineNode("x", NodeKind.EXTRACT, 0, 0);
        split.splitMode = org.example.core.model.SplitMode.SEPARATE;
        PipelineNode compress = new PipelineNode("c", NodeKind.COMPRESS, 0, 0);
        compress.outputDestination = "/tmp/out.pdf";
        m.nodes.add(split);
        m.nodes.add(compress);
        m.connections.add(new Connection("s", "x"));
        m.connections.add(new Connection("x", "c"));   // split feeds another step → invalid

        assertTrue(PipelineValidator.validate(m).stream()
            .anyMatch(e -> "x".equals(e.nodeId()) && e.message().toLowerCase().contains("last step")));
    }

    @Test
    void validChainHasNoErrors() {
        PipelineModel m = new PipelineModel();
        m.nodes.add(source("s", "/tmp/a.pdf", "/tmp/b.pdf"));
        PipelineNode merge = new PipelineNode("m", NodeKind.MERGE, 0, 0);
        merge.outputDestination = "/tmp/out.pdf";
        m.nodes.add(merge);
        m.connections.add(new Connection("s", "m"));

        assertTrue(PipelineValidator.validate(m).isEmpty());
    }

    @Test
    void propagatesCountsThroughMapAndReduce() throws Exception {
        PipelineModel m = new PipelineModel();
        m.nodes.add(source("s", "/tmp/a.pdf", "/tmp/b.pdf", "/tmp/c.pdf"));
        m.nodes.add(new PipelineNode("r", NodeKind.ROTATE, 0, 0));
        m.nodes.add(new PipelineNode("mg", NodeKind.MERGE, 0, 0));
        m.connections.add(new Connection("s", "r"));
        m.connections.add(new Connection("r", "mg"));

        assertEquals(3, PipelineGraph.outputCount(m, "r")); // map preserves count
        assertEquals(1, PipelineGraph.outputCount(m, "mg")); // reduce collapses
    }
}
