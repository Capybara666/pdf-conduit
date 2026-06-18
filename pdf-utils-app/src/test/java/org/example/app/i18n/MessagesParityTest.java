package org.example.app.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Ensures the English base bundle and the Polish bundle define the same keys. */
class MessagesParityTest {

    private static final ResourceBundle.Control NO_FALLBACK =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    @Test
    void englishAndPolishHaveTheSameKeys() {
        var en = new TreeSet<>(ResourceBundle.getBundle("i18n.messages", Locale.ENGLISH, NO_FALLBACK).keySet());
        var pl = new TreeSet<>(ResourceBundle.getBundle("i18n.messages", Locale.of("pl"), NO_FALLBACK).keySet());

        var missingInPl = new TreeSet<>(en); missingInPl.removeAll(pl);
        var extraInPl = new TreeSet<>(pl);   extraInPl.removeAll(en);

        assertFalse(en.isEmpty(), "English bundle is empty");
        assertEquals(java.util.Set.of(), missingInPl, "keys missing in Polish: " + missingInPl);
        assertEquals(java.util.Set.of(), extraInPl, "keys only in Polish: " + extraInPl);
    }
}
