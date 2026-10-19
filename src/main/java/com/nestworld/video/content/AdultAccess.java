package com.nestworld.video.content;

import com.nestworld.video.Category;
import com.nestworld.video.CinemaMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

/** Who may see 18+ videos: the permission node {@code nestworldvideo.adult}, defaulting to ContentConfig. */
public final class AdultAccess {
    public static final PermissionNode<Boolean> NODE = new PermissionNode<>(CinemaMod.MOD_ID, "adult", PermissionTypes.BOOLEAN,
            (player, uuid, context) -> switch (ContentConfig.ADULT_DEFAULT.get()) {
                case "everyone" -> true;
                case "nobody" -> false;
                default -> player != null && player.hasPermissions(2);
            });

    private AdultAccess() {}

    public static void registerNodes(PermissionGatherEvent.Nodes event) { event.addNodes(NODE); }

    public static boolean canView(ServerPlayer player) {
        try { return PermissionAPI.getPermission(player, NODE); }
        catch (RuntimeException e) { return player.hasPermissions(2); }
    }

    /** Whether the video at {@code url} is marked 18+ in the server's catalogue. */
    public static boolean isAdultUrl(String url) {
        if (url == null || url.isBlank() || CinemaMod.videoConfig == null || CinemaMod.videoConfig.getCategories() == null) return false;
        for (Category category : CinemaMod.videoConfig.getCategories()) {
            if (category == null || category.getVideos() == null) continue;
            for (var video : category.getVideos())
                if (video != null && url.equals(video.getUrl())) return category.isAdult(ratings()) || video.isAdultMarked();
        }
        return false;
    }

    public static java.util.List<String> ratings() {
        try { return ContentConfig.ADULT_RATINGS.get().stream().map(String::toLowerCase).toList(); }
        catch (RuntimeException e) { return java.util.List.of("rx"); }
    }
}
