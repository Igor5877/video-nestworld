package com.nestworld.video.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/** Keeps the server relay and the clients from being pointed at private networks by a hostile playlist, catalogue or server. */
public final class UrlSafety {
    private UrlSafety() {}

    /** Loopback, private, link-local (cloud metadata 169.254.169.254), CGNAT, unique-local IPv6, multicast and "this host". */
    public static boolean isPrivate(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) return (b[0] & 0xFF) == 0 || ((b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64);   // 0.0.0.0/8, 100.64.0.0/10
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;                                                    // fc00::/7
    }

    private static URI parse(String url) throws IOException {
        URI uri;
        try { uri = URI.create(url); } catch (RuntimeException e) { throw new IOException("bad URL"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new IOException("only http(s) is allowed");
        if (uri.getHost() == null || uri.getRawUserInfo() != null) throw new IOException("bad host");
        return uri;
    }

    /** Server side: the host must resolve only to public addresses (checked right before connecting). */
    public static void requirePublic(String url) throws IOException {
        URI uri = parse(url);
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost()))
                if (isPrivate(address)) throw new IOException("private address refused: " + uri.getHost());
        } catch (UnknownHostException e) {
            throw new IOException("unknown host: " + uri.getHost());
        }
    }

    /**
     * Client side, no DNS: http(s) only (plus file: in self-tests), no credentials, and no loopback / private literal
     * host unless it is the very server the player joined (a home server's relay lives on its LAN address).
     */
    public static boolean clientMayOpen(String url, String joinedHost) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.equals("file")) return TEST_MODE;
            if (!scheme.equals("http") && !scheme.equals("https")) return false;
            String host = uri.getHost();
            if (host == null || uri.getRawUserInfo() != null) return false;
            String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
            String joined = joinedHost == null ? "" : joinedHost.replace("[", "").replace("]", "");
            if (bare.equalsIgnoreCase(joined)) return true;
            String lower = bare.toLowerCase(Locale.ROOT);
            if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local") || lower.endsWith(".internal")) return false;
            if (lower.matches("[0-9.]+") || lower.contains(":")) {          // an IP literal: no DNS involved
                try { return !isPrivate(InetAddress.getByName(lower)); } catch (UnknownHostException e) { return false; }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final boolean TEST_MODE = Boolean.getBoolean("nestworldvideo.selftest") || Boolean.getBoolean("nestworldvideo.ffmpegtest")
            || Boolean.getBoolean("nestworldvideo.guitest") || Boolean.getBoolean("nestworldvideo.shadertest");
}
