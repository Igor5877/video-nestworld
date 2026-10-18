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
        builder.pop();
        CLIENT_SPEC = builder.build();
    }

    private NestworldConfig() {}
}
