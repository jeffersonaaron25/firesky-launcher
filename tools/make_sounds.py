#!/usr/bin/env python3
"""Synthesise FireSky's navigation sounds. Run: python3 tools/make_sounds.py

All sounds are generated here from sine waves and filtered noise, so there is nothing to license.
The move sound is one soft marimba-like note; the app shifts its pitch per tile to play a scale.
"""
import math
import random
import struct
import wave
from pathlib import Path

RATE = 44100
OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/raw"


def note(freq, dur, decay, partials=((1, 1.0), (4.0, 0.18), (9.2, 0.05)), click=0.0, bend=0.0):
    """A struck bar: a fundamental plus inharmonic overtones that die faster, with an optional
    tiny downward pitch bend and a soft noise click at the start."""
    n = int(RATE * dur)
    rnd = random.Random(7)
    out = []
    phase = [0.0] * len(partials)
    for i in range(n):
        t = i / RATE
        f0 = freq * (1 + bend * math.exp(-t * 60))
        s = 0.0
        for k, (mult, amp) in enumerate(partials):
            phase[k] += 2 * math.pi * f0 * mult / RATE
            s += amp * math.sin(phase[k]) * math.exp(-t * decay * (1 + mult * 0.35))
        if click:
            s += click * (rnd.random() * 2 - 1) * math.exp(-t * 900)
        attack = min(1.0, t / 0.0025)
        out.append(s * attack)
    return out


def mix(*tracks):
    n = max(len(t) + off for t, off in tracks)
    out = [0.0] * n
    for t, off in tracks:
        for i, v in enumerate(t):
            out[off + i] += v
    return out


def room(x, wet=0.18):
    """A small, soft room: a few quiet echoes, enough to sound polished rather than dry."""
    taps = [(int(RATE * d), g) for d, g in ((0.031, 0.5), (0.047, 0.35), (0.073, 0.22), (0.11, 0.12))]
    out = x + [0.0] * int(RATE * 0.15)
    for i in range(len(out)):
        acc = 0.0
        for d, g in taps:
            j = i - d
            if 0 <= j < len(x):
                acc += x[j] * g
        out[i] += acc * wet
    return out


def write(name, samples, peak=0.6):
    m = max(abs(v) for v in samples) or 1.0
    k = peak / m
    fade = int(RATE * 0.01)
    for i in range(fade):
        samples[-1 - i] *= i / fade
    OUT.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUT / f"{name}.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v * k)) * 32767)) for v in samples))
    print(f"{name}.wav  {len(samples) / RATE * 1000:.0f} ms")


# Move: a short wooden tap at C6. The app pitches it up and down a pentatonic scale.
write("sfx_move", room(note(1046.5, 0.16, 38, click=0.25, bend=0.02), wet=0.12), peak=0.5)

# Select: two notes rising a fifth, bell-like and a little longer.
bell = ((1, 1.0), (2.0, 0.25), (3.0, 0.1), (5.4, 0.04))
a = note(1318.5, 0.5, 9, partials=bell, click=0.08)
b = note(1975.5, 0.6, 8, partials=bell)
write("sfx_select", room(mix((a, 0), (b, int(RATE * 0.07))), wet=0.25), peak=0.55)

# Back: the same pair, falling.
c = note(1568.0, 0.3, 14, partials=bell)
d = note(1046.5, 0.4, 12, partials=bell)
write("sfx_back", room(mix((c, 0), (d, int(RATE * 0.06))), wet=0.2), peak=0.45)

# Edge: a muted low bump when there is nowhere further to go.
write("sfx_edge", room(note(196.0, 0.12, 45, partials=((1, 1.0), (2.7, 0.2)), click=0.15, bend=0.08), wet=0.08), peak=0.5)
