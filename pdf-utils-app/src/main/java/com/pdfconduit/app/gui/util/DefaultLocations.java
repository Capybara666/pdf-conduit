package com.pdfconduit.app.gui.util;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sensible default output locations, following common OS conventions:
 * a {@code pdf-conduit} folder inside the user's Documents directory
 * (falling back to the home directory), and a default result file name.
 *
 * <p>Named distinctly from {@code com.pdfconduit.core.util.OutputPaths} (which only
 * ensures a path's parent directory exists) to avoid confusion between the two.
 */
public final class DefaultLocations {

    public static final String DEFAULT_FILE = "pdf_conduit_result.pdf";

    private DefaultLocations() {}

    /** {@code ~/Documents/pdf-conduit} (or {@code ~/pdf-conduit} if Documents is absent). */
    public static Path defaultDir() {
        String home = System.getProperty("user.home", ".");
        Path documents = Path.of(home, "Documents");
        Path base = Files.isDirectory(documents) ? documents : Path.of(home);
        return base.resolve("pdf-conduit");
    }

    /** Default single-file destination: {@code <defaultDir>/pdf_conduit_result.pdf}. */
    public static Path defaultFile() {
        return defaultDir().resolve(DEFAULT_FILE);
    }
}
