package org.example.app.gui;

import javafx.css.CssParser;
import javafx.css.Stylesheet;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Parses each bundled stylesheet (headless — no Stage/Toolkit) so CSS syntax
 * errors or a broken {@code @import} are caught by the build rather than only
 * surfacing as silent warnings at runtime.
 */
class StylesheetParseTest {

    private static final String[] THEMES = {
        "/css/light.css", "/css/dark.css", "/css/nord.css",
        "/css/dracula.css", "/css/solarized.css", "/css/sunset.css"
    };

    @Test
    void everyThemeParsesAndResolvesItsImport() throws Exception {
        CssParser parser = new CssParser();
        for (String path : THEMES) {
            URL url = getClass().getResource(path);
            assertNotNull(url, "missing stylesheet: " + path);
            Stylesheet sheet = parser.parse(url);
            assertNotNull(sheet, "failed to parse: " + path);
            // Each palette is tiny on its own; a non-trivial rule count proves the
            // @import "base.css" was resolved and merged in.
            assertFalse(sheet.getRules().size() < 10,
                path + " parsed but @import did not resolve (only "
                    + sheet.getRules().size() + " rules)");
        }
    }
}
