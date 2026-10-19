package com.nestworld.video.security;

import java.nio.charset.StandardCharsets;

/** Cheap sanity checks of what a video host sends, so a hacked host cannot slip web pages, scripts or programs into the stream. */
public final class MediaChecks {
    private MediaChecks() {}

    /** An HLS playlist must be a text file that starts with #EXTM3U. */
    public static boolean looksLikePlaylist(byte[] data) {
        int start = data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF ? 3 : 0;   // BOM
        return data.length >= start + 7 && new String(data, start, 7, StandardCharsets.ISO_8859_1).equals("#EXTM3U");
    }

    /**
     * A segment (or key) must not be a web page, script, archive or executable. Hosts that disguise segments as images
     * are fine: only content that is clearly something else is refused.
     */
    public static boolean looksLikeSegment(byte[] data, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        if (type.startsWith("text/html") || type.contains("javascript") || type.contains("json") || type.contains("xml")
                || type.contains("x-msdownload") || type.contains("zip") || type.contains("x-sh")) return false;
        if (data.length < 4) return true;
        int a = data[0] & 0xFF, b = data[1] & 0xFF, c = data[2] & 0xFF;
        if (a == '<' || a == '{' || a == '[') return false;                         // html / xml / json
        if (a == '#' && b == '!') return false;                                      // shell script
        if (a == 'M' && b == 'Z') return false;                                      // Windows executable
        if (a == 0x7F && b == 'E' && c == 'L') return false;                         // Linux executable
        if (a == 'P' && b == 'K') return false;                                      // zip / jar
        if (a == 0xCA && b == 0xFE && c == 0xBA) return false;                       // Java class / Mach-O fat
        return true;
    }

    /** Removes control characters (and trims) from text an outside service sends, and limits its length. */
    public static String cleanText(String value, int max) {
        if (value == null) return "";
        var out = new StringBuilder(Math.min(value.length(), max));
        for (int i = 0; i < value.length() && out.length() < max; i++) {
            char ch = value.charAt(i);
            if (ch == '\u00a7' || Character.isISOControl(ch)) continue;           // § is Minecraft's formatting code
            out.append(ch);
        }
        return out.toString().trim();
    }
}
