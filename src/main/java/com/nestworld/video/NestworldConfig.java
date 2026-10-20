package com.nestworld.video;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client-side safeguards for worlds with many visible video screens. */
public final class NestworldConfig {
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_STREAMS;
    public static final ForgeConfigSpec.IntValue MAX_STREAM_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue SMOOTH_SCALING;
    public static final ForgeConfigSpec.IntValue MAX_VIDEO_HEIGHT;
    public static final ForgeConfigSpec.ConfigValue<String> DECODER;
    public static final ForgeConfigSpec.BooleanValue SPATIAL_AUDIO;
    public static final ForgeConfigSpec.BooleanValue MIPMAPS;
    public static final ForgeConfigSpec.BooleanValue STREAM_CACHE;
    public static final ForgeConfigSpec.IntValue DECODE_SCREENS;
    public static final ForgeConfigSpec.BooleanValue AUTO_VSYNC, DEBUG_STATS;
    public static final ForgeConfigSpec.IntValue SPATIAL_SOFTNESS;
    public static final ForgeConfigSpec.BooleanValue ACOUSTICS;
    public static final ForgeConfigSpec.IntValue SCREEN_GLOW, SCREEN_GLOW_STRENGTH;
    public static final ForgeConfigSpec.IntValue ACOUSTICS_REVERB, ACOUSTICS_QUALITY, ACOUSTICS_MUFFLE;
    public static final ForgeConfigSpec.IntValue SHARPNESS;
    public static final ForgeConfigSpec.IntValue SHADER_TINT_RED, SHADER_TINT_GREEN, SHADER_TINT_BLUE;
    public static final ForgeConfigSpec.ConfigValue<String> UPSCALER;
    public static final ForgeConfigSpec.ConfigValue<String> RENDER_MODE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("video");
        MAX_ACTIVE_STREAMS = builder
                .comment("Maximum number of HLS/video decoders opened at once on this client.",
                        "A large wall uses one stream; distant screens wait until a slot is free.")
                .defineInRange("max_active_streams", 8, 1, 64);
        DECODE_SCREENS = builder
                .comment("How many screens play at once on this client: the one you look at and the nearest ones. The others keep",
                        "showing their last picture without decoding or downloading anything until you come back to them.")
                .defineInRange("decode_screens", 2, 1, 16);
        MAX_STREAM_DISTANCE = builder
                .comment("Maximum distance in blocks for an active video stream on this client.")
                .defineInRange("max_stream_distance", 128, 16, 512);
        SMOOTH_SCALING = builder
                .comment("Scale video with the mod's scaling shader (see video.upscaler) instead of plain bilinear filtering.",
                        "Under a shader pack it also enlarges each new frame 2x before the pack draws it. Disable if your GPU struggles.")
                .define("smooth_scaling", true);
        MAX_VIDEO_HEIGHT = builder
                .comment("Maximum video height for HLS streams with several qualities (0 = automatic, best available).",
                        "Changed from the quality button in the screen menu; no effect on single-quality files.")
                .defineInRange("max_video_height", 0, 0, 4320);
        DECODER = builder
                .comment("Video decoder: \"auto\" (FFmpeg, switching to WaterMedia/VLC if FFmpeg cannot open the stream),",
                        "\"ffmpeg\" (always FFmpeg; its libraries are downloaded once into the game directory) or \"watermedia\".")
                .defineInList("decoder", "auto", java.util.List.of("auto", "ffmpeg", "watermedia"));
        SPATIAL_AUDIO = builder
                .comment("FFmpeg decoder only: play the sound from the screen in 3D (stereo is split into a left and a right",
                        "speaker at the screen's edges). Takes effect for streams started after the change.")
                .define("spatial_audio", true);
        STREAM_CACHE = builder
                .comment("Keep the HLS stream in memory on this computer (FFmpeg decoder): the part already played stays (jumping back",
                        "is instant) and the next 30 s or so are downloaded ahead (jumping forward is instant). Up to 192 MB of memory.")
                .define("stream_cache", true);
        SPATIAL_SOFTNESS = builder
                .comment("How gentle the stereo image of the screen is, 0 (wide, strongly panned) to 100 (almost mono).",
                        "Softer values blend the left and right speaker and keep the panning angle small when you stand close.")
                .defineInRange("spatial_softness", 60, 0, 100);
        SCREEN_GLOW = builder
                .comment("Colour light from a playing screen, following the picture: every lit tile of the screen carries the colour of its part of the picture",
                        "and the shader pack (patched BSL with Multicolored Blocklight) spreads it through the room. Only under a shader pack (Oculus/Iris).",
                        "0 off, 1..3 on (the light uses an 8x5 grid of the picture either way).")
                .defineInRange("screen_glow", 2, 0, 3);
        SCREEN_GLOW_STRENGTH = builder
                .comment("Brightness of that colour light in percent, 80 = as tuned; the shader pack has its own settings on top.")
                .defineInRange("screen_glow_strength", 80, 0, 100);
        ACOUSTICS = builder
                .comment("Room acoustics for the screen sound (needs the FFmpeg decoder with spatial audio): walls muffle the sound,",
                        "doors and openings let it through, and halls add reverb. Uses OpenAL EFX; switches itself off if unavailable.")
                .define("acoustics", true);
        ACOUSTICS_REVERB = builder
                .comment("Strength of the room reverb in percent (0 = only muffling by walls). The soundtrack may carry its own reverb.")
                .defineInRange("acoustics_reverb", 60, 0, 100);
        ACOUSTICS_MUFFLE = builder
                .comment("How strongly walls muffle the screen sound, in percent (100 = default; higher makes the difference between",
                        "materials and the effect of a wall stronger, 0 = walls do not muffle).")
                .defineInRange("acoustics_muffle", 100, 0, 200);
        ACOUSTICS_QUALITY = builder
                .comment("Acoustic ray quality: 1 fast, 2 balanced, 3 detailed (more rays and bounces).")
                .defineInRange("acoustics_quality", 2, 1, 3);
        AUTO_VSYNC = builder
                .comment("Temporarily enable VSync while a video screen is playing in front of you, to avoid horizontal tearing",
                        "bands at a high frame rate. Your own VSync setting is restored afterwards and never overwritten.")
                .define("auto_vsync", true);
        DEBUG_STATS = builder
                .comment("Write playback statistics (clock speed, queues, audio/video skew, decoder rate) to the log every 2 s.",
                        "For troubleshooting stutter and freezes; leave off normally.")
                .define("debug_stats", false);
        MIPMAPS = builder
                .comment("FFmpeg decoder only: build mipmaps with anisotropic filtering so distant screens do not shimmer.")
                .define("mipmaps", true);
        UPSCALER = builder
                .comment("How a video is enlarged when its screen is bigger than the video: \"edge\" follows the direction of",
                        "lines (like AMD FSR 1.0's upscaler) for smoother diagonals without halos, \"bicubic\" is a plain",
                        "sharp filter, \"anime4k\" (experimental) additionally sharpens line art. Needs video.smooth_scaling. Under a shader pack each new frame is enlarged 2x this way before the pack draws it.")
                .defineInList("upscaler", "edge", java.util.List.of("edge", "bicubic", "anime4k"));
        SHARPNESS = builder
                .comment("Extra sharpening of the picture in the scaling shader, 0 (off) to 100.",
                        "Only applies with video.smooth_scaling.")
                .defineInRange("sharpness", 0, 0, 100);
        builder.comment("Colour correction for the picture while a shader pack is active, in percent per channel.",
                "Shader packs grade the whole image and may tint it (for example BSL makes pure white slightly cream).",
                "Lower red and green to cancel a warm tint. Measured with BSL v10.1.1 on its default settings: 75 / 82 / 100",
                "makes white neutral (about 8% darker). Too low turns white blue. 100 / 100 / 100 = off.");
        SHADER_TINT_RED = builder.defineInRange("shaderpack_red", 100, 50, 100);
        SHADER_TINT_GREEN = builder.defineInRange("shaderpack_green", 100, 50, 100);
        SHADER_TINT_BLUE = builder.defineInRange("shaderpack_blue", 100, 50, 100);
        RENDER_MODE = builder
                .comment("How the picture is drawn: \"auto\" switches to Minecraft's own render pipeline when a shader pack",
                        "(Iris/Oculus) is active, \"vanilla_pipeline\" always uses it, \"direct\" never does.")
                .defineInList("render_mode", "auto", java.util.List.of("auto", "vanilla_pipeline", "direct"));
        builder.pop();
        CLIENT_SPEC = builder.build();
    }

    private NestworldConfig() {}
}
