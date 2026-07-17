package com.pdfconduit.app.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Ensures every translated bundle defines exactly the same keys as the English base. */
class MessagesParityTest {

    private static final ResourceBundle.Control NO_FALLBACK =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    /** Language codes of every non-base bundle. Add a code here when adding a language. */
    private static final String[] TRANSLATIONS = { "pl", "es", "zh" };

    @Test
    void translationsHaveTheSameKeysAsEnglish() {
        var en = new TreeSet<>(
            ResourceBundle.getBundle("i18n.messages", Locale.ENGLISH, NO_FALLBACK).keySet());
        assertFalse(en.isEmpty(), "English bundle is empty");

        for (String code : TRANSLATIONS) {
            var keys = new TreeSet<>(
                ResourceBundle.getBundle("i18n.messages", Locale.of(code), NO_FALLBACK).keySet());

            var missing = new TreeSet<>(en);   missing.removeAll(keys);
            var extra   = new TreeSet<>(keys); extra.removeAll(en);

            assertEquals(java.util.Set.of(), missing, "keys missing in '" + code + "': " + missing);
            assertEquals(java.util.Set.of(), extra, "keys only in '" + code + "': " + extra);
        }
    }
}
