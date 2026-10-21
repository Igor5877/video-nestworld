#!/usr/bin/env python3
"""
Vertical video for TikTok / Shorts / Reels (1080x1920, 30 fps) from game recordings: each clip is cut, put in
the middle of the frame over a blurred copy of itself (or cropped to fill it), gets a caption, and the clips are
joined. Game sound stays; an optional music track plays under it. Loudness is normalised for phones.

    python3 tools/tiktok/make_tiktok.py tools/tiktok/plan.txt out.mp4 [--music track.mp3] [--music-volume 0.25]

Plan file, one clip per line (# starts a comment), fields separated by |:
    file | start | length | mode | caption
    cinema.mp4 | 0:12 | 3.5 | fit  | Кінотеатр у Minecraft\\nі всі бачать кадр у кадр
    2026-10-20_21.58.21.png | 0 | 3 | center | Дивимось разом
  mode: fit (whole 16:9 picture over a blurred background), crop (fills the screen, the sides are cut off) or
  center (the middle 66 % of the picture, which leaves out the game's HUD in the corners, over a blurred background).
  A screenshot (png/jpg/webp) is shown for the given length with a slow zoom in; its start is ignored.
  In the caption \\n starts a new line. Paths are relative to the plan file.

Needs ffmpeg and a font with Cyrillic letters (found with fc-match, or --font path/to/font.ttf).
"""
import argparse
import os
import pathlib
import subprocess
import sys
import tempfile

W, H, FPS = 1080, 1920, 30


def seconds(text):
    parts = [float(p) for p in text.strip().split(":")]
    total = 0.0
    for p in parts:
        total = total * 60 + p
    return total


def find_font():
    for query in ("DejaVu Sans:bold", "Noto Sans:bold", "Liberation Sans:bold", "sans:bold"):
        try:
            path = subprocess.run(["fc-match", "-f", "%{file}", query], capture_output=True, text=True).stdout.strip()
            if path and os.path.exists(path):
                return path
        except FileNotFoundError:
            break
    sys.exit("No font found: pass --font /path/to/font.ttf (it needs Cyrillic letters)")


def has_audio(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=index", "-of", "csv=p=0", str(path)],
                         capture_output=True, text=True).stdout
    return out.strip() != ""


IMAGES = (".png", ".jpg", ".jpeg", ".webp")


def size_of(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0",
                          str(path)], capture_output=True, text=True).stdout.strip().split(",")
    return int(out[0]), int(out[1])


def render_clip(src, start, length, mode, caption, font, work, index):
    """One clip as a finished vertical piece with its caption and stereo sound."""
    out = work / f"part{index:02d}.mp4"
    lines = [line for line in caption.split("\\n") if line.strip()]
    image = src.suffix.lower() in IMAGES
    prep = "[0:v]"
    if mode == "center":   # the middle of the picture: the HUD (minimap, hotbar, chat) sits in the corners and edges
        prep += "crop=iw*0.66:ih*0.66,"
    if image:   # a still picture slowly zooms in (Ken Burns); upscaled first so the zoom does not jitter
        w, h = size_of(src)
        if mode == "center": w, h = int(w * 0.66) // 2 * 2, int(h * 0.66) // 2 * 2
        frames = max(1, int(length * FPS))
        prep += (f"scale={w * 2}:{h * 2},zoompan=z='1+0.10*on/{frames}':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':"
                 f"d=1:s={w}x{h}:fps={FPS},")
    prep += "null[src];"
    if mode == "crop":
        video = prep + f"[src]scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},setsar=1[base]"
    else:
        video = prep + (f"[src]split[a][b];[a]scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},boxblur=24:4,"
                 f"eq=brightness=-0.08[bg];[b]scale={W}:-2[fg];[bg][fg]overlay=(W-w)/2:(H-h)/2,setsar=1[base]")
    font_arg = font.replace("\\", "/").replace(":", "\\:")
    # One drawtext per line, each centred (a multi-line drawtext keeps its lines left-aligned).
    texts = []
    for n, line in enumerate(lines):
        text_file = work / f"caption{index:02d}_{n}.txt"
        text_file.write_text(line.strip(), encoding="utf-8")
        text_arg = str(text_file).replace("\\", "/").replace(":", "\\:")
        texts.append(f"drawtext=fontfile='{font_arg}':textfile='{text_arg}':fontsize=66:fontcolor=white:"
                     f"borderw=6:bordercolor=black@0.85:x=(w-text_w)/2:y=h*0.13+{n * 84}")
    video += ";[base]" + ",".join(texts + [f"fps={FPS}", "format=yuv420p"]) + "[v]"
    if image:
        cmd = ["ffmpeg", "-v", "error", "-y", "-loop", "1", "-framerate", str(FPS), "-t", str(length), "-i", str(src)]
    else:
        cmd = ["ffmpeg", "-v", "error", "-y", "-ss", str(start), "-t", str(length), "-i", str(src)]
    if not image and has_audio(src):
        audio = "[0:a]aformat=sample_rates=48000:channel_layouts=stereo[a]"
    else:
        cmd += ["-f", "lavfi", "-t", str(length), "-i", "anullsrc=r=48000:cl=stereo"]
        audio = "[1:a]anull[a]"
    cmd += ["-filter_complex", video + ";" + audio, "-map", "[v]", "-map", "[a]", "-t", str(length),
            "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-c:a", "aac", "-b:a", "192k", str(out)]
    subprocess.run(cmd, check=True)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("plan")
    ap.add_argument("output")
    ap.add_argument("--music", help="music track to play under the game sound")
    ap.add_argument("--music-volume", type=float, default=0.25)
    ap.add_argument("--font")
    args = ap.parse_args()

    plan = pathlib.Path(args.plan)
    font = args.font or find_font()
    clips = []
    for number, line in enumerate(plan.read_text(encoding="utf-8").splitlines(), 1):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        fields = [f.strip() for f in line.split("|")]
        if len(fields) != 5 or fields[3] not in ("fit", "crop", "center"):
            sys.exit(f"{plan}:{number}: expected 'file | start | length | fit/crop/center | caption'")
        src = (plan.parent / fields[0]).resolve()
        if not src.exists():
            sys.exit(f"{plan}:{number}: no such file {src}")
        clips.append((src, seconds(fields[1]), seconds(fields[2]), fields[3], fields[4]))
    if not clips:
        sys.exit("the plan has no clips")

    with tempfile.TemporaryDirectory() as tmp:
        work = pathlib.Path(tmp)
        parts = [render_clip(*clip, font, work, i) for i, clip in enumerate(clips)]
        listing = work / "parts.txt"
        listing.write_text("".join(f"file '{p}'\n" for p in parts), encoding="utf-8")
        joined = work / "joined.mp4"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy", str(joined)], check=True)
        total = sum(c[2] for c in clips)
        cmd = ["ffmpeg", "-v", "error", "-y", "-i", str(joined)]
        if args.music:
            # The music fades out over the last 1.5 s and sits under the game sound.
            cmd += ["-stream_loop", "-1", "-i", args.music, "-filter_complex",
                    f"[1:a]volume={args.music_volume},afade=t=out:st={max(0, total - 1.5)}:d=1.5[m];"
                    f"[0:a][m]amix=inputs=2:duration=first:normalize=0,loudnorm=I=-14:TP=-1.5:LRA=11[a]",
                    "-map", "0:v", "-map", "[a]"]
        else:
            cmd += ["-af", "loudnorm=I=-14:TP=-1.5:LRA=11"]
        cmd += ["-t", str(total), "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-movflags", "+faststart", args.output]
        subprocess.run(cmd, check=True)
    print(f"{args.output}: {len(clips)} clips, {total:.1f} s, {W}x{H}")


if __name__ == "__main__":
    main()
