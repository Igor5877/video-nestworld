# /// script
# dependencies = ["numpy"]
# ///
"""8-bit music for the Nestworld promo (eras_nestworld.js): 120 BPM, 15.5 s, all synthesised (square lead, triangle
bass, noise drums), with a pop on every caption and a power-on sweep when the cinema screen switches on.

    uv run scripts/audio/synth_nestworld.py out.wav
"""
import sys, wave
import numpy as np

SR = 48000
BPM = 120
BEAT = 60 / BPM
TOTAL = 15.5
N = int(SR * (TOTAL + 1.0))
mix = np.zeros(N)

def note_hz(n):  # MIDI note -> Hz
    return 440.0 * 2 ** ((n - 69) / 12)

def env(n, a=0.005, d=0.08, s=0.6, r=0.05, length=None):
    length = length or n
    t = np.arange(n) / SR
    e = np.where(t < a, t / a, np.where(t < a + d, 1 - (1 - s) * (t - a) / d, s))
    rel = int(r * SR)
    if rel and n > rel:
        e[-rel:] *= np.linspace(1, 0, rel)
    return e

def add(start, sig, gain=1.0):
    i = int(start * SR)
    if i >= N: return
    sig = sig[: N - i]
    mix[i:i + len(sig)] += sig * gain

def square(f, dur, duty=0.5, vib=0.0):
    t = np.arange(int(dur * SR)) / SR
    ph = (f * t + vib * np.sin(2 * np.pi * 6 * t) * 0.01) % 1
    return np.where(ph < duty, 1.0, -1.0)

def triangle(f, dur):
    t = np.arange(int(dur * SR)) / SR
    ph = (f * t) % 1
    return 4 * np.abs(ph - 0.5) - 1

rng = np.random.default_rng(7)
def noise(dur):
    # NES-style noise: random steps held for a few samples
    n = int(dur * SR); hold = 6
    return np.repeat(rng.uniform(-1, 1, n // hold + 1), hold)[:n]

# --- intro (0 .. 1.4 s): dark room, low drone ---
add(0.0, triangle(note_hz(45), 1.6) * env(int(1.6 * SR), a=0.3, d=0.5, s=0.8, r=0.4), 0.25)
# power-on sweep (screen switches on at 1.4–2.0 s)
t = np.arange(int(0.6 * SR)) / SR
f = 200 + 1800 * (t / 0.6) ** 2
add(1.4, np.where((np.cumsum(f) / SR) % 1 < 0.5, 1.0, -1.0) * np.linspace(0.6, 0.1, len(t)), 0.18)

# --- music from 2.0 s: Am – F – C – G, 2 beats each, arpeggio lead + bass + drums ---
chords = [(57, [69, 72, 76]), (53, [65, 69, 72]), (48, [64, 67, 72]), (55, [62, 67, 71])]
start = 2.0
eighth = BEAT / 2
step = 0
tt = start
while tt < TOTAL - 0.4:
    bar = int((tt - start) / (2 * BEAT)) % 4
    root, arp = chords[bar]
    # bass on every beat, octave jump on the off-beat
    if step % 2 == 0:
        add(tt, triangle(note_hz(root - 12 if step % 4 == 0 else root), BEAT) * env(int(BEAT * SR), d=0.2, s=0.7), 0.32)
    # lead: arpeggio 16ths
    for k in range(2):
        n = arp[(step * 2 + k) % 3] + (12 if bar == 3 and k == 1 else 0)
        d = eighth / 2
        add(tt + k * d, square(note_hz(n), d * 0.9, duty=0.25) * env(int(d * 0.9 * SR), d=0.05, s=0.5, r=0.02), 0.10)
    # drums: kick on beats 1/3, snare on 2/4, hat every eighth
    beat_i = step // 2
    if step % 2 == 0 and beat_i % 2 == 0:
        kt = np.arange(int(0.18 * SR)) / SR
        add(tt, np.sin(2 * np.pi * np.cumsum(120 * np.exp(-kt * 30) + 45) / SR) * np.exp(-kt * 18), 0.55)
    if step % 2 == 0 and beat_i % 2 == 1:
        add(tt, noise(0.16) * np.exp(-np.arange(int(0.16 * SR)) / SR * 22), 0.28)
    add(tt, noise(0.03) * np.exp(-np.arange(int(0.03 * SR)) / SR * 120), 0.10)
    step += 1
    tt = start + step * eighth

# --- a "pop" on every caption (times from eras_nestworld.js: segment start + pop offset) ---
pops = [0.25, 0.75, 2.6, 3.0, 4.65, 5.05, 5.5, 6.6, 7.0, 8.6, 9.0, 9.4, 10.6, 10.9, 11.4, 12.6, 12.85, 13.2]
for i, p in enumerate(pops):
    d = 0.09
    t = np.arange(int(d * SR)) / SR
    f = note_hz(84 + (i % 3) * 3) * (1 + 0.6 * (1 - t / d))
    add(p, np.where((np.cumsum(f) / SR) % 1 < 0.5, 1.0, -1.0) * np.exp(-t * 30), 0.16)
# whoosh on transitions
for p in [2.5, 4.5, 6.5, 8.5, 10.5, 12.5]:
    d = 0.25
    t = np.arange(int(d * SR)) / SR
    add(p - 0.12, noise(d) * np.sin(np.pi * t / d) ** 2, 0.12)

# final chord: Am held and faded
for n in (57, 64, 69, 72):
    add(TOTAL - 0.4, square(note_hz(n), 1.2, duty=0.5) * env(int(1.2 * SR), a=0.01, d=0.3, s=0.4, r=0.8), 0.07)

mix = mix[: int(TOTAL * SR)]
mix *= np.minimum(1, (len(mix) - np.arange(len(mix))) / (0.3 * SR))   # fade at the very end
mix = mix / max(1e-9, np.abs(mix).max()) * 0.89
stereo = np.stack([mix, mix], axis=1)
with wave.open(sys.argv[1], 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes((stereo * 32767).astype('<i2').tobytes())
print(f'{sys.argv[1]}: {len(mix) / SR:.2f} s')
