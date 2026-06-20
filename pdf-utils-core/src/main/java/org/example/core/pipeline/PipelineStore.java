package org.example.core.pipeline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes a {@link PipelineModel} as JSON, so a pipeline can be saved
 * and re-run later (from the GUI or the CLI). {@link Path} fields are stored as
 * plain path strings.
 */
public final class PipelineStore {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeHierarchyAdapter(Path.class, new PathAdapter())
        .create();

    private PipelineStore() {}

    public static void save(PipelineModel model, Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file)) {
            GSON.toJson(model, w);
        }
    }

    public static PipelineModel load(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file)) {
            PipelineModel model = GSON.fromJson(r, PipelineModel.class);
            if (model == null || model.nodes == null) {
                throw new IOException("Not a valid pipeline file: " + file.getFileName());
            }
            return model;
        } catch (JsonParseException e) {
            throw new IOException("Could not parse pipeline file: " + e.getMessage(), e);
        }
    }

    /** Stores a {@link Path} as its string form (and back), portably. */
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
