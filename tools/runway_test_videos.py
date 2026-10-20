# /// script
# requires-python = ">=3.10"
# dependencies = ["runwayml>=5.22,<6"]
# ///
"""
Test videos for the mod, generated with Runway (Runway Dev API, text_to_video) and turned into HLS the way the video
hosts serve them: a master playlist with 1080p, 720p and 854x480 renditions (854 pixels: rows padded in the decoder,
the case that once broke FFmpeg playback), 4-second segments.

Each clip exercises one part of the mod: scaling of line art, bloom and black level, decoding of fast motion, the
colours of the screen light, and sound against picture (sync).

    uv run tools/runway_test_videos.py --dry-run          # what would be generated and what it costs
    uv run tools/runway_test_videos.py                    # all clips (needs RUNWAYML_API_SECRET and ffmpeg)
    uv run tools/runway_test_videos.py --only lineart     # one clip

Output: build/test-videos/<name>/source.mp4 and build/test-videos/<name>/master.m3u8. Play one in the self-test with
    ./gradlew runClient -Pguitest -Pguianime -Pguiglow -Pguiurl=file://$PWD/build/test-videos/lineart/master.m3u8

The API key is read by the SDK from RUNWAYML_API_SECRET; it is never passed on the command line.
"""
import argparse
import os
import pathlib
import shutil
import subprocess
import sys
import urllib.request

# Credits per second from https://docs.dev.runwayml.com/guides/pricing.md (checked 2026-10): gen4.5 12, veo3.1_fast
# with audio 15. Allowed ratio/duration per model from https://docs.dev.runwayml.com/api.md.
CLIPS = [
    {"name": "lineart", "model": "gen4.5", "ratio": "1280:720", "duration": 5, "credits_per_s": 12,
     "tests": "upscaling (edge / Anime4K), sharpening",
     "prompt": "2D hand-drawn anime scene, clean thin black outlines and flat cel-shaded colours, a girl with long hair "
               "standing in a field of tall grass under a clear sky, wind gently moving the grass and her hair, slow "
               "horizontal camera pan, crisp line art, no film grain, no blur"},
    {"name": "dark", "model": "gen4.5", "ratio": "1280:720", "duration": 5, "credits_per_s": 12,
     "tests": "bloom, black level, contrast of the screen light",
     "prompt": "A dark room at night lit only by a single candle flame in the centre, deep black shadows around it, the "
               "flame flickers softly and casts warm light on a wooden table, static camera, high contrast, cinematic"},
    {"name": "motion", "model": "gen4.5", "ratio": "1280:720", "duration": 5, "credits_per_s": 12,
     "tests": "decoding fast motion with fine detail, distant-screen shimmer",
     "prompt": "Fast drone flight low over a dense city of small rooftops, windows and streets full of fine detail, "
               "sharp daylight, quick forward motion with a slight turn, very detailed, no motion blur"},
    {"name": "colours", "model": "gen4.5", "ratio": "1280:720", "duration": 5, "credits_per_s": 12,
     "tests": "colours of the screen light, smooth changes",
     "prompt": "Close view of a wall of neon signs in a night street, bright saturated red, blue, green and magenta "
               "lights switching on and off one after another, strong colour changes across the frame, static camera"},
    {"name": "sync", "model": "veo3.1_fast", "ratio": "1920:1080", "duration": 6, "audio": True, "credits_per_s": 15,
     "tests": "sound against picture, sync between viewers",
     "prompt": "A drummer in a bright studio hits a single snare drum once per second with a clearly visible stick "
               "strike, each hit makes a sharp loud crack, nothing else moves, static camera facing the drum"},
]

# Renditions: name, width, height, video bitrate (kbit/s).
LADDER = [("1080p", 1920, 1080, 4500), ("720p", 1280, 720, 2500), ("480p", 854, 480, 900)]


def cost(clip):
    return clip["credits_per_s"] * clip["duration"]


def generate(clip, target: pathlib.Path):
    from runwayml import RunwayML, TaskFailedError
    client = RunwayML()   # reads RUNWAYML_API_SECRET
    params = dict(model=clip["model"], prompt_text=clip["prompt"], ratio=clip["ratio"], duration=clip["duration"])
    if "audio" in clip:
        params["audio"] = clip["audio"]
    print(f"[{clip['name']}] generating with {clip['model']} ({clip['duration']} s, ~{cost(clip)} credits)...", flush=True)
    try:
        task = client.text_to_video.create(**params).wait_for_task_output()
    except TaskFailedError as e:
        raise SystemExit(f"[{clip['name']}] Runway task failed: {e.task_details}")
    url = task.output[0]   # temporary: download it right away
    target.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(url, timeout=120) as response, open(target, "wb") as out:
        shutil.copyfileobj(response, out)
    print(f"[{clip['name']}] saved {target}", flush=True)


def to_hls(source: pathlib.Path, out_dir: pathlib.Path):
    """A master playlist with every rendition of LADDER, as a video host would serve it."""
    has_audio = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=index", "-of", "csv=p=0",
                                str(source)], capture_output=True, text=True).stdout.strip() != ""
    cmd = ["ffmpeg", "-y", "-loglevel", "error", "-stream_loop", "1", "-i", str(source)]   # loop once: ~10 s, enough for jumps
    split = ";".join([f"[0:v]split={len(LADDER)}" + "".join(f"[v{i}]" for i in range(len(LADDER)))]
                     + [f"[v{i}]scale={w}:{h}:flags=bicubic,setsar=1[o{i}]" for i, (_, w, h, _) in enumerate(LADDER)])
    cmd += ["-filter_complex", split]
    stream_map = []
    for i, (_, w, h, kbps) in enumerate(LADDER):
        cmd += ["-map", f"[o{i}]"]
        if has_audio:
            cmd += ["-map", "0:a"]
        cmd += [f"-b:v:{i}", f"{kbps}k", f"-maxrate:v:{i}", f"{kbps}k", f"-bufsize:v:{i}", f"{kbps * 2}k"]
        stream_map.append(f"v:{i},a:{i}" if has_audio else f"v:{i}")
    cmd += ["-c:v", "libx264", "-preset", "veryfast", "-g", "48", "-keyint_min", "48", "-sc_threshold", "0"]
    if has_audio:
        cmd += ["-c:a", "aac", "-b:a", "128k"]
    cmd += ["-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod", "-master_pl_name", "master.m3u8",
            "-var_stream_map", " ".join(stream_map), "-hls_segment_filename", str(out_dir / "v%v" / "seg%d.ts"),
            str(out_dir / "v%v" / "index.m3u8")]
    for i in range(len(LADDER)):
        (out_dir / f"v{i}").mkdir(parents=True, exist_ok=True)
    subprocess.run(cmd, check=True)
    print(f"[{out_dir.name}] HLS ready: {out_dir / 'master.m3u8'}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--only", nargs="*", help="clip names to make (default: all)")
    parser.add_argument("--dry-run", action="store_true", help="only print what would be generated and the cost")
    parser.add_argument("--out", default="build/test-videos", help="output directory")
    parser.add_argument("--hls-only", action="store_true", help="redo the HLS from existing source.mp4 files, no API calls")
    args = parser.parse_args()

    clips = [c for c in CLIPS if not args.only or c["name"] in args.only]
    unknown = set(args.only or []) - {c["name"] for c in CLIPS}
    if unknown:
        sys.exit(f"unknown clip(s): {', '.join(sorted(unknown))}; known: {', '.join(c['name'] for c in CLIPS)}")
    total = sum(cost(c) for c in clips)
    for c in clips:
        print(f"{c['name']:8s} {c['model']:12s} {c['ratio']:9s} {c['duration']:2d} s  ~{cost(c):3d} credits  tests: {c['tests']}")
    print(f"total ~{total} credits (~${total / 100:.2f}); already generated clips are skipped")
    if args.dry_run:
        return
    if not shutil.which("ffmpeg"):
        sys.exit("ffmpeg is needed to make the HLS renditions")
    if not args.hls_only and not os.environ.get("RUNWAYML_API_SECRET"):
        sys.exit("RUNWAYML_API_SECRET is not set: create an API key in a project at https://dev.runway.com and add it to "
                 "the environment (never on the command line)")
    out = pathlib.Path(args.out)
    for c in clips:
        source = out / c["name"] / "source.mp4"
        if not source.exists():
            if args.hls_only:
                print(f"[{c['name']}] no source.mp4, skipped")
                continue
            generate(c, source)
        to_hls(source, out / c["name"])


if __name__ == "__main__":
    main()
