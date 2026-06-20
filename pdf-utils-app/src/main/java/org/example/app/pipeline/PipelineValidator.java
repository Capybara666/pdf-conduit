package org.example.app.pipeline;

import org.example.app.pipeline.Document.DocType;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.SplitMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Validates a pipeline before it can be run. */
public final class PipelineValidator {

    private PipelineValidator() {}

    public static List<ValidationError> validate(PipelineModel model) {
        List<ValidationError> errors = new ArrayList<>();

        if (model.nodes.isEmpty()) {
            errors.add(new ValidationError(null, "The pipeline is empty."));
            return errors;
        }

        Map<String, List<DocType>> types;
        try {
            types = PipelineGraph.outputTypes(model);
        } catch (PipelineGraph.CycleException e) {
            errors.add(new ValidationError(null, "The pipeline contains a cycle."));
            return errors;
        }

        for (PipelineNode n : model.nodes) {
            if (n.kind.isSource()) {
                if (n.files.isEmpty()) {
                    errors.add(new ValidationError(n.id, "Source has no files."));
                }
                for (Path f : n.files) {
                    if (!DocumentConverter.isSupported(f)) {
                        errors.add(new ValidationError(n.id,
                            "Unsupported file type: " + f.getFileName()));
                    }
                }
                continue;
            }

            // Gather incoming document types.
            List<DocType> inputs = new ArrayList<>();
            for (Connection c : model.incoming(n.id)) {
                inputs.addAll(types.getOrDefault(c.fromNodeId(), List.of()));
            }

            if (inputs.isEmpty()) {
                errors.add(new ValidationError(n.id, n.kind.label + " has no input."));
            }

            // Every operation accepts any input: non-PDF documents are converted
            // to PDF automatically (images inline, office docs via LibreOffice).

            if (model.isTerminal(n)
                    && (n.outputDestination == null || n.outputDestination.isBlank())) {
                errors.add(new ValidationError(n.id, n.kind.label + " has no output destination."));
            }

            // Separate-files output produces many files per input, which only makes
            // sense at the end of a chain — a later step can't consume a split bundle.
            if (n.kind == NodeKind.EXTRACT && n.splitMode == SplitMode.SEPARATE && !model.isTerminal(n)) {
                errors.add(new ValidationError(n.id,
                    "Split into separate files must be the last step in its chain."));
            }

            // Protect and Unlock both need a password to work.
            if ((n.kind == NodeKind.PROTECT || n.kind == NodeKind.UNLOCK)
                    && (n.password == null || n.password.isBlank())) {
                errors.add(new ValidationError(n.id, n.kind.label + " needs a password."));
            }

            // A watermark needs either text or an image.
            if (n.kind == NodeKind.WATERMARK
                    && (n.wmText == null || n.wmText.isBlank())
                    && (n.wmImage == null || n.wmImage.isBlank())) {
                errors.add(new ValidationError(n.id, n.kind.label + " needs text or an image."));
            }
        }

        return errors;
    }
}
