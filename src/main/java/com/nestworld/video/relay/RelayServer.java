package com.nestworld.video.relay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Caching HLS relay. With many viewers on one screen the server downloads every playlist and segment from the video
 * host once and hands the same bytes to all clients, instead of each client hitting the host. Only URLs that appear
 * in a playlist the relay has itself rewritten can be fetched through it, so it is not an open proxy.
 *
 * Plain JDK only (no Minecraft types), so it can be run and tested on its own.
 */
public final class RelayServer {
    private static final Logger LOG = Logger.getLogger("nestworldvideo.relay");
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");
    private static final int MAX_URLS_PER_SOURCE = 60_000, MAX_REDIRECTS = 4, MAX_PLAYLIST_BYTES = 4_000_000, MAX_SEGMENT_BYTES = 96_000_000;
    private static final String USER_AGENT = "VLC/3.0.18 LibVLC/3.0.18";

    /** A video the relay knows about: its playlists and files, addressed by small integers. */
    private static final class Source {
        final String id, originUrl, referer;
        final List<String> urls = new ArrayList<>();
        final Map<String, Integer> index = new HashMap<>();
        /** For a segment: the playlist it belongs to and its position in it (drives prefetching). */
        final Map<Integer, int[]> position = new HashMap<>();
        final Map<Integer, List<Integer>> playlistSegments = new HashMap<>();
        /** For a playlist: where each of its segments starts, in seconds from the first. */
        final Map<Integer, double[]> playlistStarts = new HashMap<>();
        /** A viewer joins at this segment (of playlist 0) until {@code aimUntilMs}: no prefetching before it. */
        volatile int aimSegment = -1;
        volatile long aimUntilMs;
        volatile long lastUsed = System.currentTimeMillis();
        Source(String id, String originUrl) { this.id = id; this.originUrl = originUrl; this.referer = originOf(originUrl); }
        synchronized int register(String url) {
            Integer known = index.get(url);
            if (known != null) return known;
            if (urls.size() >= MAX_URLS_PER_SOURCE) throw new IllegalStateException("playlist lists too many files");
            urls.add(url); index.put(url, urls.size() - 1);
            return urls.size() - 1;
        }
        synchronized String url(int n) { return n >= 0 && n < urls.size() ? urls.get(n) : null; }
    }

    private record Entry(byte[] data, String contentType, long expiresAtMs) {}

    private final int port;
    private final String bind;
    private final long maxCacheBytes;
    private final int prefetchSegments;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();   // redirects are followed by hand, each hop checked
    private final Map<String, Source> sourcesById = new ConcurrentHashMap<>();
    private final Map<String, Source> sourcesByUrl = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(256, 0.75f, true);
    private long cacheBytes;
    private final Map<String, CompletableFuture<Entry>> inFlight = new ConcurrentHashMap<>();
    private final String salt;
    private HttpServer server;
    private ExecutorService workers;
    private ScheduledExecutorService maintenance;
    private final AtomicLong originBytes = new AtomicLong(), servedBytes = new AtomicLong(), hits = new AtomicLong(), misses = new AtomicLong();

    /** Whether playlists may point at private/loopback addresses. */
    private final java.util.function.BooleanSupplier allowPrivateOrigins;

    public RelayServer(String bind, int port, int cacheMb, int prefetchSegments) {
        this(bind, port, cacheMb, prefetchSegments, () -> RelayConfig.ALLOW_PRIVATE_ORIGINS.get());
    }

    public RelayServer(String bind, int port, int cacheMb, int prefetchSegments, java.util.function.BooleanSupplier allowPrivateOrigins) {
        this.allowPrivateOrigins = allowPrivateOrigins;
        this.bind = bind; this.port = port;
        this.maxCacheBytes = Math.max(16, cacheMb) * 1024L * 1024L;
        this.prefetchSegments = Math.max(0, prefetchSegments);
        byte[] random = new byte[12];
        new SecureRandom().nextBytes(random);
        this.salt = HexFormat.of().formatHex(random);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 128);
        workers = new ThreadPoolExecutor(8, 96, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(512),
                r -> { Thread t = new Thread(r, "nestworld-relay"); t.setDaemon(true); return t; });
        server.setExecutor(workers);
        server.createContext("/r/", this::handle);
        server.start();
        maintenance = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "nestworld-relay-maint"); t.setDaemon(true); return t; });
        maintenance.scheduleAtFixedRate(this::maintain, 60, 60, TimeUnit.SECONDS);
        LOG.info("Nestworld Video relay listening on " + bind + ":" + port + " (cache " + maxCacheBytes / 1048576 + " MB)");
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (workers != null) workers.shutdownNow();
        if (maintenance != null) maintenance.shutdownNow();
        server = null;
    }

    public boolean isRunning() { return server != null; }
    /** The port it listens on (the one the system picked when started with port 0). */
    public int port() { return server != null ? server.getAddress().getPort() : port; }

    /** Path (starting with /r/) that clients use instead of {@code originUrl}. */
    public String pathFor(String originUrl) {
        Source source = sourcesByUrl.computeIfAbsent(originUrl, u -> {
            Source created = new Source(Integer.toHexString((salt + u).hashCode()) + Long.toHexString(Double.doubleToLongBits(Math.random())), u);
            sourcesById.put(created.id, created);
            return created;
        });
        source.lastUsed = System.currentTimeMillis();
        return "/r/" + source.id + "/index.m3u8";
    }

    /** Start downloading the playlist of a video that is about to be relayed, so the first requests are cache hits. */
    public void warm(String originUrl) {
        pathFor(originUrl);
        Source source = sourcesByUrl.get(originUrl);
        if (workers == null || source == null) return;
        workers.execute(() -> { try { playlist(source, originUrl, 0); } catch (Exception ignored) { /* best effort */ } });
    }

    /**
     * Start downloading the segments of {@code originUrl} (a media playlist) from {@code positionMs} on, before anyone
     * asks: a viewer that joins a running video needs that part first, not the beginning.
     */
    public void prefetchAt(String originUrl, long positionMs) {
        pathFor(originUrl);
        Source source = sourcesByUrl.get(originUrl);
        if (workers == null || source == null) return;
        try {
            workers.execute(() -> {
                try {
                    playlist(source, originUrl, 0);
                    List<Integer> list; double[] starts;
                    synchronized (source) { list = source.playlistSegments.get(0); starts = source.playlistStarts.get(0); }
                    if (list == null || starts == null || starts.length != list.size()) return;
                    int i = 0;
                    while (i + 1 < starts.length && starts[i + 1] * 1000 <= positionMs) i++;
                    source.aimSegment = i; source.aimUntilMs = System.currentTimeMillis() + 20_000L;
                    for (int k = i; k < Math.min(list.size(), i + 1 + prefetchSegments); k++) {
                        String url = source.url(list.get(k));
                        if (url != null && cacheGet(url) == null && !inFlight.containsKey(url)) {
                            if (k == i) fetch(source, url, false);   // the one needed first, right away
                            else workers.execute(() -> { try { fetch(source, url, false); } catch (Exception ignored) { /* best effort */ } });
                        }
                    }
                } catch (Exception ignored) { /* best effort: the decoder fetches what it needs anyway */ }
            });
        } catch (RejectedExecutionException ignored) { /* busy: nothing lost */ }
    }

    // ---- request handling -------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String[] parts = exchange.getRequestURI().getPath().split("/");   // "", "r", id, kind, n
            if (parts.length < 4) { reply(exchange, 404, "text/plain", new byte[0]); return; }
            Source source = sourcesById.get(parts[2]);
            if (source == null) { reply(exchange, 404, "text/plain", new byte[0]); return; }
            source.lastUsed = System.currentTimeMillis();
            Entry entry;
            if (parts[3].equals("index.m3u8")) {
                entry = playlist(source, source.originUrl, 0);
            } else if (parts.length >= 5 && (parts[3].equals("p") || parts[3].equals("s"))) {
                int n = Integer.parseInt(parts[4]);
                String url = source.url(n);
                if (url == null) { reply(exchange, 404, "text/plain", new byte[0]); return; }
                if (parts[3].equals("p")) entry = playlist(source, url, n);
                else { entry = fetch(source, url, false); prefetchAfter(source, n); }
            } else { reply(exchange, 404, "text/plain", new byte[0]); return; }
            sendWithRange(exchange, entry);
        } catch (NumberFormatException e) {
            reply(exchange, 404, "text/plain", new byte[0]);
        } catch (Exception e) {
            LOG.warning("relay request failed: " + e);
            try { reply(exchange, 502, "text/plain", new byte[0]); } catch (IOException ignored) { }
        } finally {
            exchange.close();
        }
    }

    private void sendWithRange(HttpExchange exchange, Entry entry) throws IOException {
        byte[] data = entry.data();
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            try {
                String[] bounds = range.substring(6).split("-", -1);
                int from = bounds[0].isEmpty() ? Math.max(0, data.length - Integer.parseInt(bounds[1])) : Integer.parseInt(bounds[0]);
                int to = bounds.length < 2 || bounds[1].isEmpty() || bounds[0].isEmpty() ? data.length - 1 : Math.min(data.length - 1, Integer.parseInt(bounds[1]));
                if (from <= to && from < data.length) {
                    exchange.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + data.length);
                    exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
                    exchange.getResponseHeaders().add("Content-Type", entry.contentType());
                    exchange.sendResponseHeaders(206, to - from + 1);
                    try (OutputStream out = exchange.getResponseBody()) { out.write(data, from, to - from + 1); }
                    servedBytes.addAndGet(to - from + 1);
                    return;
                }
            } catch (NumberFormatException ignored) { /* fall through to the whole file */ }
        }
        exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
        reply(exchange, 200, entry.contentType(), data);
    }

    private void reply(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        servedBytes.addAndGet(body.length);
    }

    // ---- playlists ----------------------------------------------------------------------------------------

    /** The rewritten playlist at {@code url}: every file it mentions now points back at this relay. */
    private Entry playlist(Source source, String url, int playlistIndex) throws Exception {
        String key = "rewritten:" + source.id + ":" + url;
        Entry cached = cacheGet(key);
        if (cached != null) return cached;
        Entry raw = fetch(source, url, true);
        String text = new String(raw.data(), StandardCharsets.UTF_8);
        boolean finished = text.contains("#EXT-X-ENDLIST");
        URI base = URI.create(url);
        StringBuilder out = new StringBuilder(text.length() + 256);
        List<Integer> segments = new ArrayList<>();
        List<Double> starts = new ArrayList<>();
        double clock = 0, duration = 0;
        for (String line : text.split("\r?\n", -1)) {
            if (line.length() > 4096) throw new IOException("playlist line too long");
            String trimmed = line.trim();
            if (trimmed.isEmpty()) { out.append(line).append('\n'); continue; }
            if (trimmed.startsWith("#EXTINF:")) {
                try { duration = Double.parseDouble(trimmed.substring(8).split(",", 2)[0].trim()); } catch (NumberFormatException e) { duration = 0; }
            }
            if (trimmed.startsWith("#")) {
                Matcher m = URI_ATTRIBUTE.matcher(line);
                if (m.find()) {
                    int n = source.register(base.resolve(m.group(1)).toString());
                    String kind = isPlaylist(m.group(1)) ? "p" : "s";
                    line = line.substring(0, m.start(1)) + "/r/" + source.id + "/" + kind + "/" + n + line.substring(m.end(1));
                }
                out.append(line).append('\n');
                continue;
            }
            String absolute = base.resolve(trimmed).toString();
            int n = source.register(absolute);
            boolean list = isPlaylist(trimmed);
            if (!list) { segments.add(n); starts.add(clock); clock += Math.max(0, duration); duration = 0; }
            out.append("/r/").append(source.id).append('/').append(list ? "p" : "s").append('/').append(n).append('\n');
        }
        if (!segments.isEmpty()) synchronized (source) {
            source.playlistSegments.put(playlistIndex, segments);
            source.playlistStarts.put(playlistIndex, starts.stream().mapToDouble(Double::doubleValue).toArray());
            for (int i = 0; i < segments.size(); i++) source.position.put(segments.get(i), new int[]{playlistIndex, i});
        }
        // A finished (VOD) playlist never changes; a live one is refreshed constantly.
        Entry result = new Entry(out.toString().getBytes(StandardCharsets.UTF_8), "application/vnd.apple.mpegurl",
                System.currentTimeMillis() + (finished ? 600_000L : 1_500L));
        cachePut(key, result);
        return result;
    }

    private static boolean isPlaylist(String reference) {
        String path = reference;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        return path.toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }

    // ---- fetching and caching -----------------------------------------------------------------------------

    private void prefetchAfter(Source source, int segment) {
        if (prefetchSegments == 0 || workers == null) return;
        int[] pos;
        List<Integer> list;
        synchronized (source) {
            pos = source.position.get(segment);
            list = pos == null ? null : source.playlistSegments.get(pos[0]);
        }
        if (list == null) return;
        // The decoder reads the beginning only to learn the format while a viewer joins further on: do not download ahead of that.
        if (pos[0] == 0 && pos[1] < source.aimSegment && System.currentTimeMillis() < source.aimUntilMs) return;
        for (int i = 1; i <= prefetchSegments && pos[1] + i < list.size(); i++) {
            String url = source.url(list.get(pos[1] + i));
            if (url != null && cacheGet(url) == null && !inFlight.containsKey(url))
                try { workers.execute(() -> { try { fetch(source, url, false); } catch (Exception ignored) { /* prefetch is best effort */ } }); }
                catch (RejectedExecutionException ignored) { return; }
        }
    }

    /** Bytes of {@code url}, from the cache or from the video host (one download shared by all waiting requests). */
    private Entry fetch(Source source, String url, boolean playlist) throws Exception {
        Entry cached = cacheGet(url);
        if (cached != null) { hits.incrementAndGet(); return cached; }
        CompletableFuture<Entry> mine = new CompletableFuture<>();
        CompletableFuture<Entry> existing = inFlight.putIfAbsent(url, mine);
        if (existing != null) { hits.incrementAndGet(); return existing.get(60, TimeUnit.SECONDS); }
        misses.incrementAndGet();
        try {
            // A short network hiccup must not cost the decoder a segment: try again a couple of times.
            Entry entry = null;
            for (int attempt = 1; ; attempt++) {
                try { entry = download(source, url, playlist); break; }
                catch (java.net.http.HttpTimeoutException | java.net.ConnectException | RetryableException e) {
                    if (attempt >= (playlist ? 2 : 3)) throw e;
                    Thread.sleep(250L * attempt * attempt);
                }
                catch (IOException e) {
                    if (attempt >= (playlist ? 2 : 3) || e.getMessage() == null || !e.getMessage().contains("closed")) throw e;
                    Thread.sleep(250L * attempt * attempt);
                }
            }
            cachePut(url, entry);
            mine.complete(entry);
            return entry;
        } catch (Exception e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(url, mine);
        }
    }

    /** The host answered with a server error (5xx): worth asking again. */
    private static final class RetryableException extends IOException { RetryableException(String m) { super(m); } }

    private Entry download(Source source, String url, boolean playlist) throws Exception {
        {
            HttpResponse<java.io.InputStream> response = null;
            String current = url;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                // A playlist is data from a third party: never let it send the server into a private network.
                if (!allowPrivateOrigins.getAsBoolean()) com.nestworld.video.security.UrlSafety.requirePublic(current);
                else if (!current.startsWith("http")) throw new IOException("only http(s) is allowed");
                HttpRequest request = HttpRequest.newBuilder(URI.create(current)).timeout(Duration.ofSeconds(45))
                        .header("User-Agent", USER_AGENT).header("Referer", source.referer).GET().build();
                response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int code = response.statusCode();
                if (code / 100 != 3) break;
                String location = response.headers().firstValue("Location").orElse(null);
                response.body().close();
                if (location == null || hop == MAX_REDIRECTS) throw new IOException("too many or empty redirects for " + safe(url));
                current = URI.create(current).resolve(location).toString();
            }
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                String why = "origin answered " + response.statusCode() + " for " + safe(url);
                throw response.statusCode() >= 500 ? new RetryableException(why) : new IOException(why);
            }
            byte[] body;
            try (var in = response.body()) {
                long limit = playlist ? MAX_PLAYLIST_BYTES : MAX_SEGMENT_BYTES;
                body = in.readNBytes((int) limit + 1);
                if (body.length > limit) throw new IOException("origin response too large for " + safe(url));
            }
            originBytes.addAndGet(body.length);
            String type = response.headers().firstValue("Content-Type").orElse(playlist ? "application/vnd.apple.mpegurl" : "video/mp2t");
            // What the host sends is checked before anyone downstream sees it.
            if (playlist ? !com.nestworld.video.security.MediaChecks.looksLikePlaylist(body)
                    : !com.nestworld.video.security.MediaChecks.looksLikeSegment(body, type))
                throw new IOException("origin sent unexpected content (" + type + ") for " + safe(url));
            return new Entry(body, type, System.currentTimeMillis() + (playlist ? 5_000L : 900_000L));
        }
    }

    private synchronized Entry cacheGet(String key) {
        Entry entry = cache.get(key);
        if (entry == null) return null;
        if (entry.expiresAtMs() < System.currentTimeMillis()) { cacheBytes -= entry.data().length; cache.remove(key); return null; }
        return entry;
    }

    private synchronized void cachePut(String key, Entry entry) {
        Entry old = cache.put(key, entry);
        if (old != null) cacheBytes -= old.data().length;
        cacheBytes += entry.data().length;
        Iterator<Map.Entry<String, Entry>> it = cache.entrySet().iterator();
        while (cacheBytes > maxCacheBytes && it.hasNext()) {
            Map.Entry<String, Entry> eldest = it.next();
            if (eldest.getKey().equals(key)) continue;
            cacheBytes -= eldest.getValue().data().length;
            it.remove();
        }
    }

    private void maintain() {
        long now = System.currentTimeMillis();
        sourcesByUrl.entrySet().removeIf(e -> {
            boolean idle = now - e.getValue().lastUsed > 2 * 3600_000L;
            if (idle) sourcesById.remove(e.getValue().id);
            return idle;
        });
        synchronized (this) { cache.entrySet().removeIf(e -> { boolean old = e.getValue().expiresAtMs() < now; if (old) cacheBytes -= e.getValue().data().length; return old; }); }
        if (served() > 0)
            LOG.info(String.format(Locale.ROOT, "Nestworld Video relay: served %.1f MB, fetched from hosts %.1f MB, %d hits / %d misses, cache %.0f MB",
                    servedBytes.get() / 1048576.0, originBytes.get() / 1048576.0, hits.get(), misses.get(), cacheBytes / 1048576.0));
    }
    private long served() { return servedBytes.get(); }

    static String originOf(String address) {
        try { URI uri = URI.create(address); return uri.getScheme() + "://" + uri.getHost() + "/"; } catch (RuntimeException e) { return ""; }
    }
    private static String safe(String url) { int q = url.indexOf('?'); return q < 0 ? url : url.substring(0, q) + "?<redacted>"; }
}
