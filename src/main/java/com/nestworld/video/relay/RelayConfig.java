package com.nestworld.video.relay;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server-side settings of the video relay (config/nestworldvideo-common.toml). Off by default. */
public final class RelayConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> BIND_ADDRESS, PUBLIC_HOST;
    public static final ForgeConfigSpec.IntValue PORT, MIN_VIEWERS, DIRECT_AFTER_SECONDS, CACHE_MB, PREFETCH_SEGMENTS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_PRIVATE_ORIGINS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("relay");
        ENABLED = builder
                .comment("Let this server cache the video streams and hand them to players, so many viewers of one screen",
                        "load the video host only once. The relay speaks HTTP on its own port (below); open that port in the firewall.")
                .define("enabled", false);
        BIND_ADDRESS = builder
                .comment("Address the relay listens on (0.0.0.0 = all interfaces).")
                .define("bind_address", "0.0.0.0");
        PORT = builder
                .comment("TCP port of the relay. Must be reachable by the players.")
                .defineInRange("port", 25580, 1024, 65535);
        PUBLIC_HOST = builder
                .comment("Host name or IP players should use to reach the relay. Empty = the address they used to join the server.")
                .define("public_host", "");
        MIN_VIEWERS = builder
                .comment("A screen switches from direct streaming to the relay when at least this many players watch it.")
                .defineInRange("min_viewers", 3, 1, 1000);
        DIRECT_AFTER_SECONDS = builder
                .comment("Go back to direct streaming only after the viewers stayed below the threshold this long.")
                .defineInRange("direct_after_seconds", 60, 5, 3600);
        CACHE_MB = builder
                .comment("Memory used to keep downloaded video segments.")
                .defineInRange("cache_mb", 512, 32, 8192);
        PREFETCH_SEGMENTS = builder
                .comment("How many segments the relay downloads ahead of the players.")
                .defineInRange("prefetch_segments", 4, 0, 30);
        ALLOW_PRIVATE_ORIGINS = builder
                .comment("Let the relay fetch from private/loopback addresses (e.g. a video host inside your own network).",
                        "Keep false otherwise: it stops a hostile playlist from making the server call into your internal network.")
                .define("allow_private_origins", false);
        builder.pop();
        SPEC = builder.build();
    }

    private RelayConfig() {}
}
