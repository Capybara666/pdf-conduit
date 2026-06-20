package org.example.app.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link I18n#bindText} is the primitive every relocalisable widget relies on:
 * it must set the text immediately and re-apply it whenever the language changes,
 * so the UI re-translates in place without being torn down and rebuilt.
 */
class I18nBindTextTest {

    private I18n.Language original;

    @org.junit.jupiter.api.BeforeEach
    void remember() { original = I18n.getCurrent(); }

    @AfterEach
    void restore() { I18n.setLanguage(original); }

    @Test
    void appliesTheCurrentTextImmediately() {
        I18n.setLanguage(I18n.Language.ENGLISH);
        var holder = new AtomicReference<String>();

        I18n.bindText(holder::set, "menu.theme");

        assertEquals("Theme", holder.get());
    }

    @Test
    void reappliesTheTextWhenLanguageChanges() {
        I18n.setLanguage(I18n.Language.ENGLISH);
        var holder = new AtomicReference<String>();
        I18n.bindText(holder::set, "menu.theme");

        I18n.setLanguage(I18n.Language.POLISH);

        assertEquals("Motyw", holder.get());
    }
}
