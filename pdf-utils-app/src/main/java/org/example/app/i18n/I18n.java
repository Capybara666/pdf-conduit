package org.example.app.i18n;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

/**
 * Tiny localisation helper. Strings live in {@code i18n/messages*.properties}
 * (UTF-8). English is the base bundle; Polish is {@code messages_pl} and Spanish
 * is {@code messages_es} and Chinese is {@code messages_zh}. The chosen
 * language is persisted like the theme, and listeners are notified so the UI can
 * rebuild itself live.
 */
public final class I18n {

    public enum Language {
        ENGLISH("English", "en"),
        POLISH("Polski", "pl"),
        SPANISH("Español", "es"),
        CHINESE("中文", "zh");

        public final String displayName;
        public final String code;

        Language(String displayName, String code) {
            this.displayName = displayName;
            this.code = code;
        }
    }

    private static final String BUNDLE = "i18n.messages";
    private static final Preferences PREFS = Preferences.userNodeForPackage(I18n.class);
    private static final List<Runnable> listeners = new ArrayList<>();

    private static Language current = readStored();
    private static ResourceBundle bundle = load(current);

    private I18n() {}

    private static Language readStored() {
        try {
            return Language.valueOf(PREFS.get("language", "ENGLISH"));
        } catch (IllegalArgumentException e) {
            return Language.ENGLISH;
        }
    }

    // No-fallback control so requesting English never accidentally loads the
    // host machine's default-locale bundle.
    private static ResourceBundle load(Language lang) {
        return ResourceBundle.getBundle(BUNDLE, Locale.of(lang.code),
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    public static Language getCurrent() { return current; }

    public static void setLanguage(Language lang) {
        if (lang == current) return;
        current = lang;
        PREFS.put("language", lang.name());
        bundle = load(lang);
        for (Runnable r : List.copyOf(listeners)) r.run();
    }

    /** Register a callback invoked after the language changes (e.g. to relocalise the UI). */
    public static void addListener(Runnable r) { listeners.add(r); }

    /**
     * Applies {@code t(key, args)} to {@code setter} now and again after every
     * language change, so a widget re-translates in place instead of being
     * destroyed and rebuilt (which would discard the user's work). Use this for
     * static labels; for text computed from live state, register a
     * {@link #addListener} that recomputes it.
     */
    public static void bindText(java.util.function.Consumer<String> setter, String key, Object... args) {
        setter.accept(t(key, args));
        listeners.add(() -> setter.accept(t(key, args)));
    }

    /** Look up a key; supports {@link MessageFormat} arguments. Returns the key if missing. */
    public static String t(String key, Object... args) {
        String value;
        try {
            value = bundle.getString(key);
        } catch (Exception e) {
            return key;
        }
        return args.length == 0 ? value : MessageFormat.format(value, args);
    }
}
