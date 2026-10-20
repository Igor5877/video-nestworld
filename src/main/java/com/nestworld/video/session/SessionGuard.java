package com.nestworld.video.session;

import com.nestworld.video.block.VideoScreenBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

/**
 * Session protection: whoever starts a video owns the session, and while it is active nobody else (except operators)
 * may stop, switch or otherwise control it. It ends when the video is stopped, the owner switches the protection off or
 * releases it, or {@value #GRACE_MS} ms after the owner left the game. Checked on the server for every control packet.
 *
 * <p>On top of that a screen has an owner (who built it) and an access setting ({@link ScreenAccess}): only the owner,
 * the owner and a list of players, or everybody. The owner, operators and players with the permission
 * {@code nestworldvideo.control} may always control a screen, whoever leads the session.
 */
public final class SessionGuard {
    public static final long GRACE_MS = 120_000;
    /** Control any screen regardless of its owner and of sessions; by default operators (level 2) have it. */
    public static final PermissionNode<Boolean> CONTROL_NODE = new PermissionNode<>(com.nestworld.video.CinemaMod.MOD_ID, "control",
            PermissionTypes.BOOLEAN, (player, uuid, context) -> player != null && player.hasPermissions(2));
    private static final Map<UUID, Long> LEFT = new ConcurrentHashMap<>();
    private SessionGuard() {}

    public static void onLogout(ServerPlayer player) { LEFT.put(player.getUUID(), System.currentTimeMillis()); }
    public static void onLogin(ServerPlayer player) { LEFT.remove(player.getUUID()); }
    public static void clear() { LEFT.clear(); }

    private static boolean active(VideoScreenBlockEntity screen) { return !screen.isStopped() && !screen.getCurrentVideoUrl().isBlank(); }

    private static long msSinceLeft(UUID owner) {
        Long left = LEFT.get(owner);
        return left == null ? -1 : System.currentTimeMillis() - left;
    }

    public static void registerNodes(net.minecraftforge.server.permission.events.PermissionGatherEvent.Nodes event) { event.addNodes(CONTROL_NODE); }

    /** An operator or a player given {@code nestworldvideo.control}. */
    public static boolean isAdmin(ServerPlayer player) {
        try { return PermissionAPI.getPermission(player, CONTROL_NODE); }
        catch (RuntimeException e) { return player.hasPermissions(2); }
    }

    /** The screen's owner, an operator or a player with {@code nestworldvideo.control}: may change who controls the screen. */
    public static boolean canManage(ServerPlayer player, VideoScreenBlockEntity screen) {
        return isAdmin(player) || player.getUUID().equals(screen.getScreenOwner());
    }

    /** The access setting lets this player in (does not look at the session). */
    public static boolean hasAccess(ServerPlayer player, VideoScreenBlockEntity screen) {
        return SessionRules.accessAllows(screen.getAccess(), screen.getScreenOwner(), canManage(player, screen), screen.isTrusted(player.getUUID()));
    }

    public static boolean canControl(ServerPlayer player, VideoScreenBlockEntity screen) {
        if (canManage(player, screen)) return true;
        if (!hasAccess(player, screen)) return false;
        UUID owner = screen.getSessionOwner();
        return SessionRules.allowed(screen.isSessionProtection(), false, owner, player.getUUID(), active(screen),
                owner != null && player.server.getPlayerList().getPlayer(owner) != null, owner == null ? -1 : msSinceLeft(owner), GRACE_MS);
    }

    /** Tells a refused player why. */
    public static void deny(ServerPlayer player, VideoScreenBlockEntity screen) {
        if (!hasAccess(player, screen)) {
            String name = screen.getScreenOwnerName().isBlank() ? "власник" : screen.getScreenOwnerName();
            player.displayClientMessage(Component.literal(screen.getAccess() == ScreenAccess.LIST
                    ? "Цим екраном керують лише " + name + " і гравці з його списку."
                    : "Цим екраном керує лише " + name + "."), true);
            screen.syncToClient(player);
            return;
        }
        UUID owner = screen.getSessionOwner();
        long grace = owner == null ? -1 : SessionRules.graceLeftMs(player.server.getPlayerList().getPlayer(owner) != null, msSinceLeft(owner), GRACE_MS);
        String name = screen.getSessionOwnerName().isBlank() ? "інший гравець" : screen.getSessionOwnerName();
        player.displayClientMessage(Component.literal(grace < 0
                ? "Сеанс зараз веде " + name + ": керувати може лише він (або оператор)."
                : "Сеанс вів " + name + ", він вийшов із гри. Захист зніметься приблизно за " + (grace / 1000 + 1) + " с."), true);
        screen.syncToClient(player);
    }

    /** The player started or resumed the session: they own it now. */
    public static void claim(ServerPlayer player, VideoScreenBlockEntity screen) { screen.setSessionOwner(player); }
}
