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
    public static final ForgeConfigSpec.IntValue SPATIAL_SOFTNESS;
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
        MAX_STREAM_DISTANCE = builder
                .comment("Maximum distance in blocks for an active video stream on this client.")
                .defineInRange("max_stream_distance", 128, 16, 512);
        SMOOTH_SCALING = builder
                .comment("Scale video with a bicubic (Catmull-Rom) shader instead of plain bilinear filtering.",
                        "Smooths stair-steps on large screens; disable if your GPU struggles.")
                .define("smooth_scaling", true);
        MAX_VIDEO_HEIGHT = builder
                .comment("Maximum video height for HLS streams with several qualities (0 = automatic, best available).",
                        "Changed from the quality button in the screen menu; no effect on single-quality files.")
                .defineInRange("max_video_height", 0, 0, 4320);
        DECODER = builder
                .comment("Video decoder: \"auto\" (WaterMedia/VLC, switching to FFmpeg if VLC cannot open the stream),",
                        "\"ffmpeg\" (always FFmpeg; its libraries are downloaded once into the game directory) or \"watermedia\".")
                .defineInList("decoder", "auto", java.util.List.of("auto", "ffmpeg", "watermedia"));
        SPATIAL_AUDIO = builder
                .comment("FFmpeg decoder only: play the sound from the screen in 3D (stereo is split into a left and a right",
                        "speaker at the screen's edges). Takes effect for streams started after the change.")
                .define("spatial_audio", true);
        SPATIAL_SOFTNESS = builder
                .comment("How gentle the stereo image of the screen is, 0 (wide, strongly panned) to 100 (almost mono).",
                        "Softer values blend the left and right speaker and keep the panning angle small when you stand close.")
                .defineInRange("spatial_softness", 60, 0, 100);
        MIPMAPS = builder
                .comment("FFmpeg decoder only: build mipmaps with anisotropic filtering so distant screens do not shimmer.")
                .define("mipmaps", true);
        UPSCALER = builder
                .comment("How a video is enlarged when its screen is bigger than the video: \"edge\" follows the direction of",
                        "lines (like AMD FSR 1.0's upscaler) for smoother diagonals without halos, \"bicubic\" is a plain",
                        "sharp filter. Needs video.smooth_scaling and no shader pack.")
                .defineInList("upscaler", "edge", java.util.List.of("edge", "bicubic"));
        SHARPNESS = builder
                .comment("Extra sharpening of the picture in the scaling shader, 0 (off) to 100.",
                        "Only applies with video.smooth_scaling and when no shader pack is active.")
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
