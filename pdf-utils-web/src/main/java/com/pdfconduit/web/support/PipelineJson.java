package com.pdfconduit.web.support;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.pdfconduit.core.pipeline.PipelineModel;

import java.lang.reflect.Type;
import java.nio.file.Path;

/**
 * Parses a {@link PipelineModel} from a JSON string in memory (no temp file), using the same
 * on-disk shape {@code core.pipeline.PipelineStore} writes — {@link Path} fields serialised as
 * plain strings. The web backend never touches host paths itself; a source node's {@code files}
 * entries are treated purely as <em>names</em> matched against uploaded parts.
 */
public final class PipelineJson {

    private static final Gson GSON = new GsonBuilder()
        .registerTypeHierarchyAdapter(Path.class, new PathAdapter())
        .create();

    private PipelineJson() {}

    /** Parses {@code json} into a {@link PipelineModel}; throws {@link IllegalArgumentException} on bad input. */
    public static PipelineModel parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: pipeline");
        }
        try {
            PipelineModel model = GSON.fromJson(json, PipelineModel.class);
            if (model == null || model.nodes == null) {
                throw new IllegalArgumentException("Not a valid pipeline: missing nodes.");
            }
            return model;
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Could not parse pipeline JSON: " + e.getMessage(), e);
        }
    }

    private static final class PathAdapter implements JsonSerializer<Path>, JsonDeserializer<Path> {
        @Override
        public JsonElement serialize(Path src, Type type, JsonSerializationContext ctx) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public Path deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) {
            return Path.of(json.getAsString());
        }
    }
}
