package com.pdfconduit.app.gui.util;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.io.BufferedInputStream;
import java.net.URL;
import java.util.prefs.Preferences;

/**
 * Short UI sound effects, played through the JDK's own audio stack
 * ({@code javax.sound.sampled}) rather than JavaFX media — the JDK path goes via
 * the platform's default mixer (ALSA/PulseAudio/PipeWire) and is reliably audible
 * on Linux desktops, where JavaFX's bundled gstreamer-lite often plays silently.
 *
 * <p>Playback is best-effort: any failure (sounds disabled, missing resource, no
 * free audio line) is swallowed, so callers never need to guard. A fresh
 * {@link Clip} is opened per play and closed when it finishes, so rapid or
 * overlapping plays don't fight over a single line.
 */
public final class Sfx {

    private static final Preferences PREFS = Preferences.userNodeForPackage(Sfx.class);
    private static boolean enabled = PREFS.getBoolean("sfxEnabled", true);

    private Sfx() {}

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean value) {
        enabled = value;
        PREFS.putBoolean("sfxEnabled", value);
    }

    /** Plays the success chime if sounds are enabled. */
    public static void playDone() { play("/sfx/done.wav"); }

    /** Plays the (subtler) error sound if sounds are enabled. */
    public static void playError() { play("/sfx/error.wav"); }

    private static void play(String resource) {
        if (!enabled) return;
        URL url = Sfx.class.getResource(resource);
        if (url == null) return;
        // Open and start off the calling (often the FX) thread — loading a clip
        // does a little I/O we don't want to block the UI on.
        Thread t = new Thread(() -> {
            try (AudioInputStream in =
                     AudioSystem.getAudioInputStream(new BufferedInputStream(url.openStream()))) {
                Clip clip = AudioSystem.getClip();
                clip.open(in);
                clip.addLineListener(ev -> {
                    if (ev.getType() == LineEvent.Type.STOP) clip.close();
                });
                clip.start();
            } catch (Throwable ignored) {
                // Audio is non-essential; never let a missing/locked device break the UI.
            }
        }, "sfx-play");
        t.setDaemon(true);
        t.start();
    }
}
