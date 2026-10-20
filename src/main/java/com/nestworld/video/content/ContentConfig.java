package com.nestworld.video.content;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/** Server settings for adult (18+) videos (config/nestworldvideo-server-content.toml). */
public final class ContentConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> ADULT_DEFAULT;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ADULT_RATINGS;
    public static final ForgeConfigSpec.BooleanValue SCREEN_LIGHT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("adult_content");
        ADULT_DEFAULT = builder
                .comment("Who may watch videos marked 18+ when the permission \"nestworldvideo.adult\" is not set for them:",
                        "\"ops\" (operators only), \"everyone\" or \"nobody\". Others see a \"18+\" placeholder on the screen and hear nothing.",
                        "With a permissions mod (e.g. LuckPerms) grant or deny the node \"nestworldvideo.adult\" per player or group.")
                .defineInList("default_access", "ops", List.of("ops", "everyone", "nobody"));
        ADULT_RATINGS = builder
                .comment("Hikka age ratings that count as 18+. \"rx\" is hentai; add \"r_plus\" to include strong ecchi/nudity.",
                        "A single video or release can also be marked by hand with \"adult\": true in videos.json.")
                .defineList("adult_ratings", List.of("rx"), o -> o instanceof String);
        builder.pop();
        builder.push("screen");
        SCREEN_LIGHT = builder
                .comment("A playing screen gives light (block light 10) so the room around it is not pitch dark.",
                        "A big screen lights an even grid of its blocks, not all of them. Takes effect for screens that start or stop playing afterwards.")
                .define("light_when_playing", true);
        builder.pop();
        SPEC = builder.build();
    }

    private ContentConfig() {}
}
