package com.nestworld.video.client.util;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.nestworld.video.NestworldConfig;
import com.nestworld.video.ffmpeg.DecoderListener;
import com.nestworld.video.ffmpeg.FfmpegLibs;
import com.nestworld.video.ffmpeg.HlsVariants;
import com.nestworld.video.ffmpeg.StreamInfo;
import com.nestworld.video.ffmpeg.VideoDecoder;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.awt.Dimension;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * FFmpeg-backed implementation: frames are decoded on a worker thread, uploaded to a GL texture on the render
 * thread when the media clock reaches them, and audio plays through Minecraft's OpenAL context, which also
 * serves as the clock. Identical on every operating system, unlike VLC's per-platform plugins and outputs.
 */
public final class FfmpegDisplay extends Display {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String USER_AGENT = "VLC/3.0.18 LibVLC/3.0.18";
    private static final long STARTUP_TIMEOUT_NANOS = 90_000_000_000L;
    private static final long SEEK_DRIFT_US = 2_000_000L;
    private static final long FRAME_BUDGET_BYTES = 96L << 20;
    /** Upper bound for the adaptive queue growth. */
    private static final long FRAME_CAP_BYTES = 320L << 20;

    private static final class VFrame {
        ByteBuffer data; int width, height, generation; long ptsUs;
    }

    private final String url, source;
    private final long createdNanos = System.nanoTime();
    private volatile VideoDecoder decoder;
    private volatile StreamInfo info;
    private volatile AlAudio audio;
    private volatile String error;
    private volatile boolean closed, eos;
    private volatile long openedNanos;
    private volatile int expectedGeneration;

    private final Object lock = new Object();
    private final ArrayDeque<VFrame> frames = new ArrayDeque<>();
    private final ArrayList<VFrame> pool = new ArrayList<>();
    private volatile int maxFrames = 6;
    /** How far the queue may grow when the audio needs the decoder to read further ahead (see onVideo). */
    private volatile int frameLimit = 6, frameCap = 6;

    // Client-thread state.
    private int textureId, textureWidth, textureHeight;
    private boolean frameShown, started, playing = true, audioClock, endReported, firstFrameLogged;
    private boolean initialSyncDone;
    private boolean mipmapped;
    private VFrame previewed;
    private long previewedPtsUs = -1;
    private volatile long pendingSyncUs = -1;
    private long seekTargetUs = -1;
    /** Quality: the cap this display was opened with, the height chosen, and the next lower height the stream offers. */
    private final int heightCap;
    private volatile int chosenHeight, lowerHeight;
    /** Stalls (no picture to show although playing) after the start: when each began, and the current one. */
    private final java.util.ArrayDeque<Long> stallStarts = new java.util.ArrayDeque<>();
    private long stallSinceNanos;
    /** When the decoder began opening the stream or the last seek, until playback starts (0 once started). */
    private volatile long waitingSinceNanos;
    private boolean lowerSuggested;
    private long startedWallNanos, mediaBaseUs, frozenUs, lastMediaUs, lastShownPtsUs;
    private long firstFrameWaitNanos, lastDiagnosticNanos, lastRenderNanos;
    // Statistics for video.debug_stats (written by the decoder thread, read by the client tick).
    private volatile long statVideoFrames, statAudioChunks, statBlockedVideoNanos, statLastVideoPts, statLastAudioPts;
    // Render-side statistics (render thread only).
    private long statShown, statSkipped, statUploadNanos, statMaxUploadNanos, statMaxGapNanos, statLastShownNanos;
    private long statShownSeen, statSkippedSeen, statUploadSeen;
    private long statWallNanos, statMediaUs, statVideoFramesSeen, statAudioChunksSeen, statBlockedSeen;
    private int baseVolume = 100;
    private double spatial = 1.0;

    public FfmpegDisplay(String url) { this(url, url); }

    public FfmpegDisplay(String url, String source) { this(url, source, 0); }

    /** {@code heightCap}: at most this tall (0 = only the player's quality setting), e.g. after the connection could not keep up. */
    public FfmpegDisplay(String url, String source, int heightCap) {
        this.url = url; this.source = source; this.heightCap = heightCap;
        Thread opener = new Thread(this::open, "nestworld-ffmpeg-open");
        opener.setDaemon(true);
        opener.start();
    }

    // ---- start-up -----------------------------------------------------------------------------------------

    private void open() {
        try {
            FfmpegLibs.prepareAsync(FMLPaths.GAMEDIR.get());
            while (!closed && FfmpegLibs.state() != FfmpegLibs.State.READY) {
                if (FfmpegLibs.state() == FfmpegLibs.State.FAILED) { error = FfmpegLibs.message(); return; }
                Thread.sleep(100);
            }
            if (closed) return;
            String referer = origin(source);
            int limit = NestworldConfig.MAX_VIDEO_HEIGHT.get();
            if (heightCap > 0) limit = limit == 0 ? heightCap : Math.min(limit, heightCap);
            // What the connection managed lately (measured by the local cache) rules out renditions it could not keep up with.
            HlsVariants.Choice choice = HlsVariants.pickChoice(source, limit, LocalStreamCache.throughputBitsPerSecond(), USER_AGENT, referer);
            chosenHeight = choice.height(); lowerHeight = choice.lowerHeight();
            String target = LocalStreamCache.wrap(choice.url());
            // A viewer joining a running video: fetch the part of the stream it starts at while the decoder opens.
            long startAtUs = pendingSyncUs;
            if (startAtUs > 0) LocalStreamCache.prefetchAt(choice.url(), (startAtUs + leadUs) / 1000L);
            VideoDecoder created = FfmpegLibs.newDecoder();
            decoder = created;
            openedNanos = System.nanoTime();
            created.open(target, USER_AGENT, referer, new Listener());
            if (closed) created.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            LOGGER.warn("Nestworld Video: FFmpeg could not start for {}: {}", safe(url), t.toString());
        }
    }

    private static String origin(String address) {
        try {
            URI uri = URI.create(address);
            return uri.getScheme() + "://" + uri.getHost() + "/";
        } catch (RuntimeException e) { return ""; }
    }

    private static String safe(String address) {
        int query = address.indexOf('?');
        return query < 0 ? address : address.substring(0, query) + "?<redacted>";
    }

    /** Decoder-thread callbacks. */
    private final class Listener implements DecoderListener {
        @Override public void onInfo(StreamInfo streamInfo) {
            int bytes = Math.max(1, streamInfo.width * streamInfo.height * 4);
            maxFrames = (int) Math.max(4, Math.min(30, FRAME_BUDGET_BYTES / bytes));
            frameLimit = maxFrames;
            frameCap = (int) Math.max(maxFrames, Math.min(60, FRAME_CAP_BYTES / bytes));
            if (streamInfo.hasAudio)
                audio = new AlAudio(streamInfo.sampleRate, streamInfo.channels, NestworldConfig.SPATIAL_AUDIO.get(),
                        NestworldConfig.SPATIAL_SOFTNESS.get() / 200f);
            if (audio != null) audio.setPitch((float) rate);
            waitingSinceNanos = System.nanoTime();   // the stream is open: from now on a slow start is the connection's fault
            info = streamInfo;
        }

        @Override public void onVideo(ByteBuffer rgba, int width, int height, int stride, long ptsUs, int generation)
                throws InterruptedException {
            VFrame frame;
            synchronized (lock) {
                long blockedFrom = System.nanoTime(), starvedSince = 0;
                while (!closed && frames.size() >= frameLimit && generation >= expectedGeneration) {
                    lock.wait(5);
                    // While nobody is looking at this screen the client tick (20 Hz) is the only consumer, which
                    // throttled the decoder (and with it the sound) to ~80% of real time. Consume due frames here.
                    if (started && System.nanoTime() - lastRenderNanos > 150_000_000L) {
                        long media = decoderClockUs();
                        while (!frames.isEmpty() && frames.peek().ptsUs <= media) pool.add(frames.poll());
                    }
                    // Streams often carry the audio behind the video. If the sound is truly running dry while the
                    // video queue is full, let the queue grow (up to a cap) so the decoder reads further ahead.
                    AlAudio output = audio;
                    if (output != null && output.isAvailable() && started && output.bufferedUs() < 250_000L) {
                        long now = System.nanoTime();
                        if (starvedSince == 0) starvedSince = now;
                        else if (now - starvedSince >= 150_000_000L) {
                            starvedSince = now;
                            if (frameLimit < frameCap) {
                                frameLimit = Math.min(frameCap, frameLimit + 6);
                                LOGGER.info("Nestworld Video: audio runs behind the video in this stream, buffering up to {} frames", frameLimit);
                            }
                        }
                    } else starvedSince = 0;
                }
                statBlockedVideoNanos += System.nanoTime() - blockedFrom;
                if (closed || generation < expectedGeneration) return;
                frame = pool.isEmpty() ? null : pool.remove(pool.size() - 1);
            }
            int size = width * height * 4;
            if (frame == null) frame = new VFrame();
            if (frame.data == null || frame.data.capacity() < size) frame.data = ByteBuffer.allocateDirect(size);
            ByteBuffer dst = frame.data;
            dst.clear();
            ByteBuffer src = rgba.duplicate();
            if (stride == width * 4) {
                src.position(0).limit(size);
                dst.put(src);
            } else {
                // Rows padded to a wider stride (e.g. 854-pixel 480p in 864-pixel rows): copy each row's pixels only.
                // The limit goes first: moving the position past the previous row's limit would throw.
                for (int row = 0; row < height; row++) {
                    src.limit(row * stride + width * 4).position(row * stride);
                    dst.put(src);
                }
            }
            dst.flip();
            frame.width = width; frame.height = height; frame.ptsUs = ptsUs; frame.generation = generation;
            statVideoFrames++; statLastVideoPts = ptsUs;
            synchronized (lock) {
                if (closed || generation < expectedGeneration) { pool.add(frame); return; }
                frames.add(frame);
                lock.notifyAll();
            }
        }

        @Override public void onAudio(ByteBuffer pcm, long ptsUs, int generation) throws InterruptedException {
            statAudioChunks++; statLastAudioPts = ptsUs;
            AlAudio output = audio;
            if (output != null && output.isAvailable()) output.offer(pcm, ptsUs, generation);
        }

        @Override public void onEnded(int generation) {
            if (generation >= expectedGeneration) eos = true;
        }

        @Override public void onError(String message, Throwable cause) {
            error = message;
            LOGGER.warn("Nestworld Video: FFmpeg failed on {}: {}", safe(url), String.valueOf(cause));
        }
    }

    // ---- Display API --------------------------------------------------------------------------------------

    @Override public String getUrl() { return url; }
    @Override public String getSourceUrl() { return source; }

    @Override public int getTextureId() {
        StreamInfo streamInfo = info;
        if (streamInfo == null || closed) return -1;
        lastRenderNanos = System.nanoTime();
        maybeStart();
        if (started) {
            long media = mediaTimeUs();
            VFrame show = null;
            synchronized (lock) {
                while (!frames.isEmpty() && frames.peek().ptsUs <= media) {
                    if (show != null) { pool.add(show); statSkipped++; }
                    show = frames.poll();
                }
                lock.notifyAll();
            }
            if (show != null) {
                long uploadStart = System.nanoTime();
                upload(show);
                long uploaded = System.nanoTime();
                statShown++; statUploadNanos += uploaded - uploadStart;
                statMaxUploadNanos = Math.max(statMaxUploadNanos, uploaded - uploadStart);
                if (statLastShownNanos != 0) statMaxGapNanos = Math.max(statMaxGapNanos, uploaded - statLastShownNanos);
                statLastShownNanos = uploaded;
                lastShownPtsUs = show.ptsUs;
                synchronized (lock) { pool.add(show); }
            }
        } else {
            // Not playing yet (or paused after a seek): show the first frame instead of a black screen.
            VFrame head;
            synchronized (lock) { head = frames.peek(); }
            if (head != null && (head != previewed || head.ptsUs != previewedPtsUs)) {
                upload(head);
                previewed = head; previewedPtsUs = head.ptsUs;
            }
        }
        return frameShown ? textureId : -1;
    }

    /** Drops frames the clock has passed without uploading them, so the decoder and the audio keep flowing off-screen. */
    /** Clock for the decoder thread: same as {@link #mediaTimeUs()} but without updating any state. */
    private long decoderClockUs() {
        if (!playing) return frozenUs;
        AlAudio output = audio;
        if (audioClock && output != null && output.isAvailable()) {
            long clock = output.clockUs();
            return clock >= 0 ? Math.max(lastMediaUs, clock) : lastMediaUs;
        }
        return mediaBaseUs + (long) ((System.nanoTime() - startedWallNanos) / 1000L * rate);
    }

    private void discardDueFrames() {
        long media = mediaTimeUs();
        synchronized (lock) {
            while (!frames.isEmpty() && frames.peek().ptsUs <= media) pool.add(frames.poll());
            lock.notifyAll();
        }
    }

    /** Waits for enough buffered media, then starts the clock at the first frame. */
    private void maybeStart() {
        if (started || !playing) return;
        VFrame head; int queuedFrames;
        synchronized (lock) { head = frames.peek(); queuedFrames = frames.size(); }
        if (head == null) return;
        long now = System.nanoTime();
        if (firstFrameWaitNanos == 0) firstFrameWaitNanos = now;
        AlAudio output = audio;
        boolean useAudio = output != null && output.isAvailable();
        boolean enoughVideo = queuedFrames >= Math.min(3, maxFrames) || eos;
        boolean enoughAudio = !useAudio || output.queuedUs() >= 150_000L || eos || now - firstFrameWaitNanos > 3_000_000_000L;
        if (!enoughVideo || !enoughAudio) return;
        if (seekStartedNanos != 0) {   // learn how far ahead to aim the next time
            long took = (now - seekStartedNanos) / 1000L;
            leadUs = Math.max(300_000L, Math.min(8_000_000L, (leadUs * 6 + (took + 150_000L) * 4) / 10));
            seekStartedNanos = 0;
            if (NestworldConfig.DEBUG_STATS.get())
                LOGGER.info("Nestworld Video: seek ready in {}ms (aiming {}ms ahead next time), first frame {}ms, shared timeline {}ms",
                        took / 1000, leadUs / 1000, head.ptsUs / 1000, timelineUs() == Long.MIN_VALUE ? -1 : timelineUs() / 1000);
        }
        long shared = timelineUs();
        if (shared != Long.MIN_VALUE && !eos) {
            // Hold the first frame until the shared timeline reaches it, so everyone starts together.
            if (shared < head.ptsUs - 40_000L) return;
            if (shared > head.ptsUs + 250_000L) {
                // Behind it: far behind means aim further ahead; a little behind means skip what has already passed.
                if (shared - head.ptsUs > SEEK_DRIFT_US) { seekTo(shared + leadUs); return; }
                synchronized (lock) {
                    while (!frames.isEmpty() && frames.peek().ptsUs < shared) pool.add(frames.poll());
                    lock.notifyAll();
                }
                if (output != null) output.discardBefore(shared);
                return;
            }
        }
        started = true;
        seekTargetUs = -1;
        firstFrameWaitNanos = 0;
        mediaBaseUs = head.ptsUs;
        lastMediaUs = head.ptsUs;
        startedWallNanos = now;
        audioClock = useAudio;
        if (useAudio) { output.start(head.ptsUs); output.setPlaying(true); }
    }

    private long mediaTimeUs() {
        if (!started) return seekTargetUs >= 0 ? seekTargetUs : lastMediaUs;
        if (!playing) return frozenUs;
        if (audioClock) {
            AlAudio output = audio;
            if (output == null || !output.isAvailable()) {
                audioClock = false;                       // sound output died: keep going on the wall clock
                mediaBaseUs = lastMediaUs; startedWallNanos = System.nanoTime();
            } else {
                long clock = output.clockUs();
                if (clock >= 0) lastMediaUs = Math.max(lastMediaUs, clock);
                return lastMediaUs;
            }
        }
        lastMediaUs = mediaBaseUs + (long) ((System.nanoTime() - startedWallNanos) / 1000L * rate);
        return lastMediaUs;
    }

    private volatile com.nestworld.video.client.glow.GlowGrid glow;
    private long lastGlowSampleNanos;

    @Override public com.nestworld.video.client.glow.GlowGrid glowGrid() { return glow; }

    /** Grid size of the colour light per quality level (1..3): the finer, the more detail the light follows. */
    private static final int[][] GLOW_GRID = {{8, 5}, {16, 9}, {32, 18}};

    /** Average colour of every cell of a grid over the frame, from a sparse set of pixels (about 10 times a second). */
    private void sampleColours(VFrame frame) {
        if (!com.nestworld.video.client.glow.ScreenGlow.enabled()) { glow = null; return; }
        long now = System.nanoTime();
        if (now - lastGlowSampleNanos < 80_000_000L) return;
        lastGlowSampleNanos = now;
        int w = frame.width, h = frame.height;
        int[] size = GLOW_GRID[Math.max(1, Math.min(3, NestworldConfig.SCREEN_GLOW.get())) - 1];
        int cols = size[0], rows = size[1];
        if (w < cols || h < rows || frame.data == null) return;
        float[] out = new float[cols * rows * 3];
        int per = 3;   // 3 x 3 pixels per cell
        for (int row = 0; row < rows; row++) for (int col = 0; col < cols; col++) {
            double r = 0, g = 0, b = 0; int n = 0;
            for (int sy = 0; sy < per; sy++) {
                int y = Math.min(h - 1, (int) ((row + (sy + 0.5) / per) * h / rows));
                for (int sx = 0; sx < per; sx++) {
                    int x = Math.min(w - 1, (int) ((col + (sx + 0.5) / per) * w / cols));
                    int i = (y * w + x) * 4;
                    if (i + 2 >= frame.data.capacity()) continue;
                    r += frame.data.get(i) & 0xFF; g += frame.data.get(i + 1) & 0xFF; b += frame.data.get(i + 2) & 0xFF; n++;
                }
            }
            if (n == 0) return;
            int o = (row * cols + col) * 3;
            out[o] = (float) (r / n / 255.0); out[o + 1] = (float) (g / n / 255.0); out[o + 2] = (float) (b / n / 255.0);
        }
        glow = new com.nestworld.video.client.glow.GlowGrid(cols, rows, out);
    }

    private void upload(VFrame frame) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (textureId == 0) {
            textureId = TextureUtil.generateTextureId();
            GlStateManager._bindTexture(textureId);
            mipmapped = NestworldConfig.MIPMAPS.get();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                    mipmapped ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            if (mipmapped && GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
                float max = GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
                GL11.glTexParameterf(GL11.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, Math.min(8f, max));
            }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        } else {
            GlStateManager._bindTexture(textureId);
        }
        // Vanilla uploads (e.g. new font glyphs when a menu opens) leave their sub-rectangle settings behind; start
        // from a clean unpack state or the frame is read with an offset and runs past the end of the buffer.
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
        if (frame.width != textureWidth || frame.height != textureHeight) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, frame.width, frame.height, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            textureWidth = frame.width; textureHeight = frame.height;
        }
        frame.data.position(0);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, frame.data);
        if (mipmapped) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        GlStateManager._bindTexture(previous);
        sampleColours(frame);
        frameShown = true;
        frameSerial++;
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            LOGGER.info("Nestworld Video: stream {} decodes at {}x{} (FFmpeg)", safe(url), frame.width, frame.height);
        }
    }

    @Override public void tick() {
        if (closed) return;
        StreamInfo streamInfo = info;
        if (streamInfo != null && pendingSyncUs >= 0 && decoder != null) applySync();
        // Playback must not depend on anyone looking at the screen: start the clock (and with it the sound) from the
        // client tick, and keep consuming frames while the renderer is not drawing this screen.
        maybeStart();
        if (started && System.nanoTime() - lastRenderNanos > 150_000_000L) discardDueFrames();
        trackEndStall();
        trackStalls();
        watchdog();
        diagnose();
        stats();
    }

    private void stats() {
        if (!NestworldConfig.DEBUG_STATS.get() || !started) { statWallNanos = 0; return; }
        long now = System.nanoTime();
        if (statWallNanos == 0) {
            statWallNanos = now; statMediaUs = mediaTimeUs(); statVideoFramesSeen = statVideoFrames;
            statAudioChunksSeen = statAudioChunks; statBlockedSeen = statBlockedVideoNanos; return;
        }
        if (now - statWallNanos < 2_000_000_000L) return;
        long wall = (now - statWallNanos) / 1000L, media = mediaTimeUs();
        int queued; synchronized (lock) { queued = frames.size(); }
        AlAudio output = audio;
        LOGGER.info("Nestworld Video stats: clock x{} (media +{}ms in {}ms wall), decoded {} fps / {} audio chunks, decoder blocked on video {}ms, "
                        + "frames {}/{} (cap {}), audio buffered {}ms {} (underruns {}), audio output latency {}ms, decoder a/v pts skew {}ms (audio - video), paused={}",
                String.format(java.util.Locale.ROOT, "%.2f", (media - statMediaUs) / (double) Math.max(1, wall)),
                (media - statMediaUs) / 1000, wall / 1000,
                String.format(java.util.Locale.ROOT, "%.1f", (statVideoFrames - statVideoFramesSeen) * 1e6 / Math.max(1, wall)),
                statAudioChunks - statAudioChunksSeen, (statBlockedVideoNanos - statBlockedSeen) / 1_000_000L,
                queued, frameLimit, frameCap,
                output == null ? -1 : output.bufferedUs() / 1000, output == null ? "none" : output.isPlaying() ? "playing" : "STOPPED",
                output == null ? 0 : output.underruns(),
                output == null || output.outputLatencyUs() < 0 ? "?" : String.valueOf(output.outputLatencyUs() / 1000),
                (statLastAudioPts - statLastVideoPts) / 1000, !playing);
        long shown = statShown, skipped = statSkipped, uploadNanos = statUploadNanos;
        LOGGER.info("Nestworld Video render: game fps {}, shown {} frames, skipped (late) {}, upload avg {}ms / max {}ms, longest gap between shown frames {}ms",
                net.minecraft.client.Minecraft.getInstance().getFps(), shown - statShownSeen, skipped - statSkippedSeen,
                String.format(java.util.Locale.ROOT, "%.2f", (uploadNanos - statUploadSeen) / 1e6 / Math.max(1, shown - statShownSeen)),
                String.format(java.util.Locale.ROOT, "%.1f", statMaxUploadNanos / 1e6), statMaxGapNanos / 1_000_000L);
        statShownSeen = shown; statSkippedSeen = skipped; statUploadSeen = uploadNanos; statMaxUploadNanos = 0; statMaxGapNanos = 0;
        statWallNanos = now; statMediaUs = media; statVideoFramesSeen = statVideoFrames;
        statAudioChunksSeen = statAudioChunks; statBlockedSeen = statBlockedVideoNanos;
    }

    private void applySync() {
        long target = pendingSyncUs;
        pendingSyncUs = -1;
        initialSyncDone = true;
        VFrame head;
        synchronized (lock) { head = frames.peek(); }
        long current = started ? mediaTimeUs() : head != null ? head.ptsUs : 0;
        if (Math.abs(current - target) <= SEEK_DRIFT_US) return;
        // While playing, the shared timeline moves on during the seek: aim ahead of it and wait for it on the first frame.
        seekTo(playing && timelineUs() >= 0 ? target + leadUs : target);
    }

    /** The shared timeline (see {@link #setTimeline}) in microseconds, below 0 while counting down to a start, Long.MIN_VALUE if unknown. */
    private volatile java.util.function.LongSupplier timeline;
    @Override public void setTimeline(java.util.function.LongSupplier timelineMs) { timeline = timelineMs; }
    private long timelineUs() {
        java.util.function.LongSupplier t = timeline;
        long ms = t == null ? Long.MIN_VALUE : t.getAsLong();
        return ms == Long.MIN_VALUE ? Long.MIN_VALUE : ms * 1000L;
    }
    /** How long a seek takes until there is enough to start (learned from the seeks of this stream), and when the current one began. */
    private long leadUs = 1_000_000L, seekStartedNanos;

    /** Counts the times playback ran dry (the network or the decoder could not keep up), for {@link #lowerQualitySuggestion()}. */
    private void trackStalls() {
        boolean dry;
        int queued;
        synchronized (lock) { dry = started && playing && !eos && frames.isEmpty(); queued = frames.size(); }
        long now = System.nanoTime();
        if (started) waitingSinceNanos = 0;
        // Not even started 8 s after opening or seeking, with almost nothing decoded: that is the longest stall of all.
        else if (playing && error == null && waitingSinceNanos != 0 && queued < 3 && now - waitingSinceNanos > 8_000_000_000L) slowStart = true;
        if (!dry) { stallSinceNanos = 0; return; }
        if (stallSinceNanos == 0) { stallSinceNanos = now; stallStarts.addLast(now); }
        while (!stallStarts.isEmpty() && now - stallStarts.peekFirst() > 45_000_000_000L) stallStarts.pollFirst();
    }

    /**
     * A lower height to reopen the stream at, once, when this viewer keeps running dry (three stalls within 45 s or one
     * longer than 2.5 s) and the stream offers one; 0 otherwise.
     */
    @Override public int lowerQualitySuggestion() {
        if (lowerSuggested || lowerHeight <= 0 || error != null) return 0;   // a failing decoder is not a slow connection
        boolean longStall = slowStart || (stallSinceNanos != 0 && System.nanoTime() - stallSinceNanos > 2_500_000_000L);
        if (!longStall && stallStarts.size() < 3) return 0;
        lowerSuggested = true;
        LOGGER.info("Nestworld Video: playback of {} keeps running dry at {}p ({} stalls in 45 s{}); suggesting {}p",
                safe(url), chosenHeight, stallStarts.size(), longStall ? ", one over 2.5 s" : "", lowerHeight);
        return lowerHeight;
    }

    private long watchMediaUs = -1, watchSinceNanos;
    /**
     * Self-heal for a frozen picture and sound: the clock stands still although decoded frames are waiting (a seek
     * used to be the only way out). Waiting for the network (no queued frames) is not a freeze and is left alone.
     */
    private void watchdog() {
        if (!started || !playing || eos || decoder == null || pendingSyncUs >= 0) { watchSinceNanos = 0; return; }
        long now = System.nanoTime(), media = mediaTimeUs();
        if (media != watchMediaUs || watchSinceNanos == 0) { watchMediaUs = media; watchSinceNanos = now; return; }
        int queued; synchronized (lock) { queued = frames.size(); }
        if (now - watchSinceNanos < 4_000_000_000L || queued == 0) return;
        AlAudio output = audio;
        LOGGER.warn("Nestworld Video: playback froze at {}ms with {} frames queued (audio {}, queued {}ms); re-seeking",
                media / 1000, queued, output == null ? "none" : output.isPlaying() ? "playing" : "stopped",
                output == null ? 0 : output.queuedUs() / 1000);
        watchSinceNanos = 0;
        seekTo(media);
    }

    private boolean slowStart;

    private void seekTo(long target) {
        waitingSinceNanos = System.nanoTime();
        seekTargetUs = target;
        seekStartedNanos = System.nanoTime();
        int generation = decoder.seek(target);
        expectedGeneration = generation;
        synchronized (lock) {
            pool.addAll(frames);
            frames.clear();
            lock.notifyAll();
        }
        AlAudio output = audio;
        if (output != null) output.flush(generation);
        started = false;
        eos = false;
        endedByStall = false;
        endReported = false;   // a repeat or a seek back must be able to report the next end again
        firstFrameWaitNanos = 0;
        lastMediaUs = target;
        frozenUs = target;
    }

    @Override public void setPlaying(boolean value) {
        if (value == playing) return;
        if (started) {
            if (!value) frozenUs = mediaTimeUs();
            else { mediaBaseUs = frozenUs; startedWallNanos = System.nanoTime(); }
        }
        playing = value;
        AlAudio output = audio;
        if (output != null && started) output.setPlaying(value);
    }

    @Override public boolean isPlaying() { return playing && started && !isEnded(); }

    /** Playback speed for keeping in step with the other viewers (sound through OpenAL pitch, the clock follows it). */
    private volatile double rate = 1.0;
    @Override public void setPlaybackRate(double value) {
        double r = Math.max(0.95, Math.min(1.05, value));
        if (Math.abs(r - rate) < 0.0005) return;
        if (started && playing && !audioClock) { mediaBaseUs = mediaTimeUs(); startedWallNanos = System.nanoTime(); }
        rate = r;
        AlAudio output = audio;
        if (output != null) output.setPitch((float) r);
    }

    @Override public void syncPosition(long positionMs) {
        pendingSyncUs = Math.max(0, positionMs) * 1000L;
        // A new target after the first one is a real seek (the user skipped or the server moved the clock).
        if (info != null && decoder != null) applySync();
    }

    @Override public boolean needsInitialSync() { return !initialSyncDone; }
    @Override public boolean isLoading() { return !closed && error == null && !started; }

    @Override public boolean isBuffering() {
        if (closed || error != null || !playing) return false;
        if (!started) return info != null;
        synchronized (lock) { return frames.isEmpty() && !eos; }
    }

    @Override public boolean isBroken() {
        if (error != null) return true;
        boolean libsReady = FfmpegLibs.state() == FfmpegLibs.State.READY;
        return libsReady && !frameShown && openedNanos != 0 && System.nanoTime() - openedNanos > STARTUP_TIMEOUT_NANOS;
    }

    /** At the very end the audio often stops a moment before the last video frames: the clock stands still, so wait it out. */
    private long eosMediaUs = Long.MIN_VALUE, eosStallSince;
    private boolean endedByStall;
    private void trackEndStall() {
        if (!eos || !started || !playing) { eosStallSince = 0; eosMediaUs = Long.MIN_VALUE; return; }
        long now = System.nanoTime(), media = mediaTimeUs();
        if (media != eosMediaUs || eosStallSince == 0) { eosMediaUs = media; eosStallSince = now; return; }
        if (now - eosStallSince > 1_500_000_000L) endedByStall = true;
    }

    @Override public boolean isEnded() {
        if (endedByStall && eos) return true;
        if (!eos || !frameShown) return false;
        synchronized (lock) { if (!frames.isEmpty()) return false; }
        AlAudio output = audio;
        return !(audioClock && output != null && output.isAvailable()) || output.isDrained();
    }

    @Override public boolean pollEnded() {
        if (endReported || !playing || !isEnded()) return false;
        endReported = true;
        return true;
    }

    @Override public long getDuration() {
        StreamInfo streamInfo = info;
        return streamInfo == null || streamInfo.durationUs <= 0 ? -1 : streamInfo.durationUs / 1000L;
    }

    @Override public long getPlaybackTime() {
        if (info == null) return -1;
        if (!started && seekTargetUs < 0 && !frameShown) return -1;
        return Math.max(0, mediaTimeUs() / 1000L);
    }

    @Override public Dimension getDimensions() {
        StreamInfo streamInfo = info;
        if (streamInfo == null || streamInfo.height <= 0) return new Dimension(0, 0);
        double aspect = streamInfo.aspectRatio > 0 ? streamInfo.aspectRatio : (double) streamInfo.width / streamInfo.height;
        return new Dimension((int) Math.round(streamInfo.height * aspect), streamInfo.height);
    }

    @Override public boolean managesTextureFilter() { return true; }
    @Override public boolean hasMipmaps() { return mipmapped && frameShown; }
    private volatile long frameSerial = -1;
    @Override public long frameSerial() { return frameSerial; }

    @Override public void setVolume(int volume) { baseVolume = Math.max(0, Math.min(200, volume)); }
    @Override public int getVolume() { return baseVolume; }

    @Override public void applySpatialVolume(double factor) {
        spatial = factor;
        AlAudio output = audio;
        if (output == null) return;
        var options = Minecraft.getInstance().options;
        float game = options.getSoundSourceVolume(SoundSource.MASTER) * options.getSoundSourceVolume(SoundSource.RECORDS);
        output.setGain((float) (baseVolume / 100.0 * spatial) * game);
    }

    @Override public void setSoundPlacement(double cx, double cy, double cz, double rx, double ry, double rz) {
        AlAudio output = audio;
        if (output != null && output.isSpatial()) output.setPlacement(cx, cy, cz, rx, ry, rz);
    }

    @Override public void setAcoustics(com.nestworld.video.client.acoustics.AcousticResult result) {
        AlAudio output = audio;
        if (output != null) output.setAcoustics(result);
    }

    @Override public int detachPicture() {
        if (!frameShown || textureId <= 0) return -1;
        int id = textureId;
        textureId = 0;   // release() leaves it alone now
        return id;
    }

    @Override public void release() {
        if (closed) return;
        closed = true;
        VideoDecoder current = decoder;
        if (current != null) current.close();
        AlAudio output = audio;
        if (output != null) output.close();
        synchronized (lock) { frames.clear(); pool.clear(); lock.notifyAll(); }
        int id = textureId;
        textureId = 0;
        if (id != 0) {
            if (RenderSystem.isOnRenderThread()) TextureUtil.releaseTextureId(id);
            else Minecraft.getInstance().execute(() -> TextureUtil.releaseTextureId(id));
        }
    }

    private void diagnose() {
        long now = System.nanoTime();
        if (now - lastDiagnosticNanos < 10_000_000_000L) return;
        lastDiagnosticNanos = now;
        int queued; synchronized (lock) { queued = frames.size(); }
        StreamInfo streamInfo = info;
        AlAudio output = audio;
        LOGGER.info("Nestworld Video FFmpeg diagnostic: libs={}, opened={}, started={}, playing={}, buffering={}, media={}ms/{}ms, "
                        + "size={}x{}, queuedFrames={}, audio={}, error={}",
                FfmpegLibs.state(), decoder != null, started, playing, isBuffering(), Math.max(-1, getPlaybackTime()),
                getDuration(), streamInfo == null ? 0 : streamInfo.width, streamInfo == null ? 0 : streamInfo.height, queued,
                output == null ? "none" : output.isAvailable() ? (audioClock ? "clock" : "idle") : "failed", error);
    }
}
