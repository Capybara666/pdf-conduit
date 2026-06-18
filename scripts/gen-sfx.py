#!/usr/bin/env python3
"""Generate the app's short success/error sound effects (no third-party assets).

Produces 16-bit mono 44.1 kHz WAV files under
pdf-utils-app/src/main/resources/sfx/. Re-run after editing to regenerate.

    python3 scripts/gen-sfx.py
"""
import math
import struct
import wave
from pathlib import Path

RATE = 44100
OUT = Path(__file__).resolve().parent.parent / \
    "pdf-utils-app/src/main/resources/sfx"


def tone(freq, t, decay):
    """A soft bell-ish partial: fundamental + a quieter octave, exp. decay."""
    env = math.exp(-decay * t)
    base = math.sin(2 * math.pi * freq * t)
    octave = 0.25 * math.sin(2 * math.pi * 2 * freq * t)
    return env * (base + octave)


def render(notes, length):
    """notes: list of (freq, start_s, decay, gain). Returns float samples."""
    n = int(RATE * length)
    buf = [0.0] * n
    for freq, start, decay, gain in notes:
        s0 = int(RATE * start)
        for i in range(s0, n):
            buf[i] += gain * tone(freq, (i - s0) / RATE, decay)
    # short linear fade-out to avoid an end click
    fade = int(RATE * 0.02)
    for i in range(n - fade, n):
        buf[i] *= (n - i) / fade
    peak = max(1e-9, max(abs(x) for x in buf))
    return [x / peak * 0.7 for x in buf]


def write(path, samples):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        frames = b"".join(struct.pack("<h", int(max(-1, min(1, x)) * 32767))
                          for x in samples)
        w.writeframes(frames)
    print("wrote", path)


# Success: a gentle rising two-note chime (C6 -> G6, a perfect fifth).
write(OUT / "done.wav", render([
    (1046.50, 0.00, 8.0, 1.0),   # C6
    (1567.98, 0.09, 7.0, 0.9),   # G6
], 0.45))

# Error: a soft low two-note fall (A4 -> E4), quieter and less bright.
write(OUT / "error.wav", render([
    (440.00, 0.00, 9.0, 1.0),    # A4
    (329.63, 0.10, 9.0, 0.9),    # E4
], 0.40))
