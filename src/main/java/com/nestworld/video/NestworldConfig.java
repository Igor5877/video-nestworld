package com.nestworld.video;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client-side safeguards for worlds with many visible video screens. */
public final class NestworldConfig {
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_STREAMS;
    public static final ForgeConfigSpec.IntValue MAX_STREAM_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue SMOOTH_SCALING;

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
        builder.pop();
        CLIENT_SPEC = builder.build();
    }

    private NestworldConfig() {}
}
