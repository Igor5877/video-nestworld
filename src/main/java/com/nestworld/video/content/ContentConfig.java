package com.nestworld.video.content;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/** Server settings for adult (18+) videos (config/nestworldvideo-server-content.toml). */
public final class ContentConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> ADULT_DEFAULT;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ADULT_RATINGS;
    public static final ForgeConfigSpec.ConfigValue<String> TMDB_KEY;

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
        builder.push("films");
        TMDB_KEY = builder
                .comment("TMDB API key (v3 key or v4 read token) for /films search and /films add. Free: themoviedb.org -> Settings -> API.",
                        "Stays on the server; never sent to players.")
                .define("tmdb_api_key", "");
        builder.pop();
        SPEC = builder.build();
    }

    private ContentConfig() {}
}
