package com.pdfconduit.web.support;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A unique, self-cleaning temp directory for one request. Uploaded parts are saved
 * under {@code in/}, operation outputs land under {@code out/}. Closing the workspace
 * recursively deletes everything.
 *
 * <p><b>Streaming rule:</b> result bytes must be read into memory <em>before</em> the
 * workspace is closed — the response must never be backed by a file in a directory that
 * {@link #close()} has already deleted.
 */
public final class TempWorkspace implements AutoCloseable {

    private final Path root;
    private final Path inDir;
    private final Path outDir;
    private final AtomicInteger seq = new AtomicInteger();

    private TempWorkspace(Path root, Path inDir, Path outDir) {
        this.root = root;
        this.inDir = inDir;
        this.outDir = outDir;
    }

    /** Creates a fresh unique workspace beneath {@code base}. */
    public static TempWorkspace create(Path base) throws IOException {
        Path root = Files.createTempDirectory(base, "req-");
        Path in = Files.createDirectories(root.resolve("in"));
        Path out = Files.createDirectories(root.resolve("out"));
        return new TempWorkspace(root, in, out);
    }

    /**
     * Saves an uploaded part into this workspace, preserving its extension (so core's
     * type detection works) and sanitising the name. Returns the saved path.
     */
    public Path save(MultipartFile file) throws IOException {
        String name = sanitize(file.getOriginalFilename());
        // Each upload gets its own numbered sub-directory: two files sharing a name never
        // collide, yet the saved file keeps its clean original name (so derived output
        // names — and the eventual download filename — stay readable).
        Path dir = Files.createDirectories(inDir.resolve(String.valueOf(seq.incrementAndGet())));
        Path dest = dir.resolve(name);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dest);
        }
        return dest;
    }

    /** The directory operations write their outputs into. */
    public Path outputDir() {
        return outDir;
    }

    /** A path for a named output inside {@link #outputDir()}. */
    public Path newOutput(String name) {
        return outDir.resolve(sanitize(name));
    }

    @Override
    public void close() {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {
            // Best-effort cleanup; a leftover temp dir is not worth failing the response.
        }
    }

    /** Reads a file fully into memory (call before {@link #close()}). */
    public static byte[] readAll(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read result file " + file, e);
        }
    }

    /**
     * Reduces an arbitrary client-supplied filename to a safe basename: strips any
     * path, drops control/reserved characters, and falls back to {@code upload} when
     * nothing usable remains. The extension is preserved for downstream type detection.
     */
    static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "upload";
        // Keep only the final path segment (defeat ../ and absolute paths).
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\x00-\\x1f]", "").strip();
        // Replace anything that is not a safe filename character.
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        // Avoid names that are only dots.
        while (name.startsWith(".")) name = name.substring(1);
        if (name.isBlank()) return "upload";
        return name;
    }
}
