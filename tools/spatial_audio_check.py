# Measures the recording of the -Pguispatial self-test (OpenAL writing to a WAV file, see tools/alsoft-wave.conf):
# level, left/right difference, high frequencies and the reverb tail of each phase.
#   ALSOFT_CONF=tools/alsoft-wave.conf ./gradlew runClient -Pguitest -Pguianime -Pguispatial -Pguiurl=file:///path/to/noise.m3u8
#   ffmpeg -i out.wav -c:a pcm_s16le out16.wav && python3 tools/spatial_audio_check.py out16.wav
import sys, wave, numpy as np
w = wave.open(sys.argv[1]); rate = w.getframerate()
x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(float).reshape(-1, 2) / 32768
win = rate // 20; n = len(x) // win
env = np.sqrt((x[:n * win] ** 2).reshape(n, win, 2).mean(axis=(1, 2)))
db = lambda v: 20 * np.log10(max(v, 1e-9))
loud = env > env.max() * 0.05
onsets = [i for i in range(20, len(env) - 40) if loud[i:i + 40].all() and not loud[i - 20:i].any()]
start = onsets[-1] - 8 * 100   # the last phase with sound (the wool hall) is phase 8; count back from it
names = ["лицем до екрана", "екран ліворуч", "екран праворуч", "стіна між", "далеко (40 бл.)", "кам'яна зала", "відкрито знову", "дерев'яна зала", "зала з вовни"]
ref = None
for k, name in enumerate(names):
    on = start + k * 100                               # 5 s per phase, in 50 ms windows
    seg = x[(on + 10) * win:(on + 55) * win]
    if len(seg) < win: break
    l, r = np.sqrt((seg ** 2).mean(axis=0)); level = np.sqrt((seg ** 2).mean())
    mono = seg.mean(axis=1); spec = np.abs(np.fft.rfft(mono)) ** 2; f = np.fft.rfftfreq(len(mono), 1 / rate)
    hf = spec[f > 4000].sum() / max(spec[f > 100].sum(), 1e-12)
    if ref is None: ref = (level, hf)
    steady = db(np.sqrt((env[on + 10:on + 50] ** 2).mean()))
    off = next((i for i in range(on + 40, on + 100) if db(env[i]) < steady - 6), on + 60)   # the sound was switched off
    tail = [db(env[off + i]) - db(level) for i in range(2, 34, 4)]
    print(f"{name:16s} рівень {db(level) - db(ref[0]):+6.1f}  Л/П {db(l) - db(r):+5.1f}  високі {db(hf) - db(ref[1]):+6.1f}  хвіст 0.1..1.5с " + " ".join(f"{v:4.0f}" for v in tail))
