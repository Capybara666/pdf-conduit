package org.example.app.pipeline;

import org.example.app.pipeline.Document.DocType;

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

            switch (n.kind) {
                case EXTRACT, COMPRESS, ROTATE -> {
                    if (inputs.stream().anyMatch(t -> t != DocType.PDF)) {
                        errors.add(new ValidationError(n.id,
                            n.kind.label + " only accepts PDF input."));
                    }
                }
                case IMAGES_TO_PDF -> {
                    if (inputs.stream().anyMatch(t -> t != DocType.IMAGE)) {
                        errors.add(new ValidationError(n.id,
                            "Images → PDF only accepts image input."));
                    }
                }
                default -> { /* MERGE accepts any mix */ }
            }

            if (model.isTerminal(n)
                    && (n.outputDestination == null || n.outputDestination.isBlank())) {
                errors.add(new ValidationError(n.id, n.kind.label + " has no output destination."));
            }
        }

        return errors;
    }
}
