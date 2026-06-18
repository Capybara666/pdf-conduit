package org.example.app.gui.util;

import javafx.scene.media.AudioClip;

import java.util.prefs.Preferences;

/**
 * Short UI sound effects. Clips are loaded lazily and cached; playback is a
 * no-op when sounds are disabled or the resource/media stack is unavailable, so
 * callers never need to guard.
 */
public final class Sfx {

    private static final Preferences PREFS = Preferences.userNodeForPackage(Sfx.class);
    private static boolean enabled = PREFS.getBoolean("sfxEnabled", true);

    private static AudioClip done;
    private static AudioClip error;

    private Sfx() {}

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean value) {
        enabled = value;
        PREFS.putBoolean("sfxEnabled", value);
    }

    /** Plays the success chime if sounds are enabled. */
    public static void playDone() {
        if (!enabled) return;
        if (done == null) done = load("/sfx/done.wav");
        play(done);
    }

    /** Plays the (subtler) error sound if sounds are enabled. */
    public static void playError() {
        if (!enabled) return;
        if (error == null) error = load("/sfx/error.wav");
        play(error);
    }

    private static AudioClip load(String resource) {
        try {
            var url = Sfx.class.getResource(resource);
            return url == null ? null : new AudioClip(url.toExternalForm());
        } catch (Throwable t) {   // missing javafx.media native libs, etc.
            return null;
        }
    }

    private static void play(AudioClip clip) {
        if (clip == null) return;
        try {
            clip.play();
        } catch (Throwable ignored) {}
    }
}
