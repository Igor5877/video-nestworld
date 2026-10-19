package com.nestworld.video.session;

import com.nestworld.video.block.VideoScreenBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session protection: whoever starts a video owns the session, and while it is active nobody else (except operators)
 * may stop, switch or otherwise control it. It ends when the video is stopped, the owner switches the protection off or
 * releases it, or {@value #GRACE_MS} ms after the owner left the game. Checked on the server for every control packet.
 */
public final class SessionGuard {
    public static final long GRACE_MS = 120_000;
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

    public static boolean canControl(ServerPlayer player, VideoScreenBlockEntity screen) {
        UUID owner = screen.getSessionOwner();
        return SessionRules.allowed(screen.isSessionProtection(), player.hasPermissions(2), owner, player.getUUID(), active(screen),
                owner != null && player.server.getPlayerList().getPlayer(owner) != null, owner == null ? -1 : msSinceLeft(owner), GRACE_MS);
    }

    /** Tells a refused player why. */
    public static void deny(ServerPlayer player, VideoScreenBlockEntity screen) {
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
