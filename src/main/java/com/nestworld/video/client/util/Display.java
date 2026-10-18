package com.nestworld.video.client.util;

import com.nestworld.video.NestworldConfig;
import net.minecraft.client.Minecraft;
import com.mojang.logging.LogUtils;
import org.watermedia.api.player.videolan.VideoPlayer;
import org.slf4j.Logger;

import java.awt.Dimension;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import org.watermedia.videolan4j.player.base.MediaPlayer;
import org.watermedia.videolan4j.player.embedded.videosurface.callback.BufferFormat;

public class Display {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DiagnosticVideoPlayer player;
    private FrameStats frameStats = new FrameStats();
    private final String url;
    private boolean isPlaying = false;
    private Boolean appliedPlaying;
    private long pendingPositionMs = -1;
    private boolean initialSyncComplete;

    // Cache preRender() result for one render frame so that all tiles in a multi-block
    // screen share the same GL texture ID and never show different video frames.
    private int cachedTextureId = -1;
    private final long createdAtNanos = System.nanoTime();
    private volatile boolean firstTextureReady;
    private boolean startupTimeoutLogged;
    private boolean startupRetried;
    private long retryAfterNanos;
    private long lastPreRenderNanos = 0;
    private long lastDiagnosticNanos;
    private long lastMediaSampleNanos;
    private long lastObservedMediaTime = -1;
    private long lastMediaProgressNanos;
    private long lastPreRenderMillis;
    private long sampledMediaTime = -1;
    private long lastLoggedDecodedFrames;
    private long lastLoggedUploadedFrames;
    // 8 ms ≈ one frame at 125 fps; safe cache window for any reasonable frame rate.
    private static final long FRAME_CACHE_NANOS = 8_000_000L;
    // Remote streams can take 30-60 seconds to produce their first frame. Allow
    // extra startup time, but don't show a loading spinner forever on failures.
    private static final long STARTUP_TIMEOUT_NANOS = 90_000_000_000L;
    // A little extra headroom makes HLS playback tolerate short network jitter.
    // VLC's default in this setup is only 1000 ms; this affects startup latency,
    // not Minecraft's server-side traffic.
    private static String[] vlcOptions() {
        // For HLS streams with several renditions VLC's default adaptive logic may stay on a low one;
        // "highest" always picks the best variant. It has no effect on plain mp4 files.
        return NestworldConfig.PREFER_HIGHEST_QUALITY.get()
                ? new String[]{"--network-caching=3000", "--adaptive-logic=highest"}
                : new String[]{"--network-caching=3000"};
    }
    private boolean resolutionLogged;
    // Seeking an HLS stream can trigger another segment fetch and visible stall.
    // Correct only larger drift and wait until the decoder has left buffering.
    private static final long SEEK_DRIFT_THRESHOLD_MS = 2_000;

    public Display(String url) {
        this.url = url;
        this.player = new DiagnosticVideoPlayer(Minecraft.getInstance(), frameStats);

        try {
            // Decode the first frame while paused. The seek from the server clock
            // can then be applied before playback begins, avoiding a visible start
            // from 0 ms followed by a jump to the synchronized position.
            this.player.setRepeatMode(false);
            this.player.startPaused(new URI(url), vlcOptions());
            this.setPlaying(true);
        } catch (Exception e) {
            this.player.release();
            throw new IllegalArgumentException("Cannot start video playback: " + url, e);
        }
    }

    public String getUrl() { return url; }

    public int getTextureId() {
        applyPendingSeek();
        applyPendingPause();
        // isReady() means libvlc_media_player_will_play(), including OPENING.
        // preRender() can return an allocated 1x1 texture before any video frame.
        if (!player.isReady() || frameStats.uploaded.get() == 0) {
            diagnose(-1);
            return -1;
        }
        long now = System.nanoTime();
        if (cachedTextureId == -1 || now - lastPreRenderNanos > FRAME_CACHE_NANOS) {
            long renderStart = System.nanoTime();
            cachedTextureId = player.preRender();
            lastPreRenderMillis = (System.nanoTime() - renderStart) / 1_000_000L;
            lastPreRenderNanos = now;
        }
        if (cachedTextureId <= 0) {
            diagnose(cachedTextureId);
            return -1;
        }
        firstTextureReady = true;
        diagnose(cachedTextureId);
        return cachedTextureId;
    }

    /** Run recovery on the client tick, never from the block renderer. */
    public void tick() {
        if (!resolutionLogged && firstTextureReady) {
            Dimension size = getDimensions();
            if (size != null && size.width > 0 && size.height > 0) {
                resolutionLogged = true;
                LOGGER.info("Nestworld Video: stream {} decodes at {}x{}", url, size.width, size.height);
            }
        }
        boolean failedBeforeFrame = frameStats.decoded.get() == 0 && player.raw() != null
                && (player.isEnded() || player.isBroken());
        if (!startupRetried && isPlaying && failedBeforeFrame && !isStartupTimedOut()) {
            long now = System.nanoTime();
            if (retryAfterNanos == 0) retryAfterNanos = now + 1_000_000_000L;
            if (now >= retryAfterNanos) {
                startupRetried = true;
                LOGGER.warn("Nestworld Video: retrying startup once after state={}, decodedFrames=0",
                        player.getStateName());
                player.stop();
                player.release();
                frameStats = new FrameStats();
                player = new DiagnosticVideoPlayer(Minecraft.getInstance(), frameStats);
                appliedPlaying = null;
                initialSyncComplete = false;
                cachedTextureId = -1;
                lastPreRenderNanos = 0;
                firstTextureReady = false;
                sampledMediaTime = lastObservedMediaTime = -1;
                lastMediaProgressNanos = 0;
                lastLoggedDecodedFrames = lastLoggedUploadedFrames = 0;
                player.setRepeatMode(false);
                player.startPaused(URI.create(url), vlcOptions());
            }
        } else {
            retryAfterNanos = 0;
        }
        applyPendingSeek();
        applyPendingPause();
    }

    private boolean canControlPlayback() {
        // A native frame proves startup reached the decoder. Do not seek or
        // consume a pause request merely because VLC says it "will play".
        return frameStats.decoded.get() > 0 && (player.isPlaying() || player.isPaused());
    }

    /** Periodically correlates VLC buffering/media-clock progress with render-thread cost and game FPS. */
    private void diagnose(int textureId) {
        long now = System.nanoTime();
        if (now - lastMediaSampleNanos >= 250_000_000L) {
            lastMediaSampleNanos = now;
            if (player.isReady()) {
                try { sampledMediaTime = player.getTime(); }
                catch (RuntimeException ignored) { /* Keep diagnostics from affecting playback. */ }
            }
            if (sampledMediaTime >= 0 && sampledMediaTime > lastObservedMediaTime) {
                lastObservedMediaTime = sampledMediaTime;
                lastMediaProgressNanos = now;
            }
        }
        if (now - lastDiagnosticNanos < 5_000_000_000L) return;
        lastDiagnosticNanos = now;
        // WaterMedia 2.1.30 can report isReady() == false while its native
        // CallbackMediaPlayerComponent is still null. Methods such as
        // getStateName() dereference that component, so do not query any of the
        // detailed player state until initialization has completed.
        if (!player.isReady()) {
            String host;
            try { host = new URI(url).getHost(); }
            catch (Exception ignored) { host = "unknown"; }
            if (player.raw() == null) {
                LOGGER.info("Nestworld Video diagnostic: host={}, state=API_INITIALIZING, texture={}", host, textureId);
            } else {
                String state = player.getStateName();
                boolean failed = player.isBroken();
                if (failed) {
                    LOGGER.warn("Nestworld Video diagnostic: host={}, state={}, broken=true, valid={}, loading={}, buffering={}, texture={}",
                            host, state, player.isValid(), player.isLoading(), player.isBuffering(), textureId);
                } else {
                    LOGGER.info("Nestworld Video diagnostic: host={}, state={}, broken=false, valid={}, loading={}, buffering={}, texture={}",
                            host, state, player.isValid(), player.isLoading(), player.isBuffering(), textureId);
                }
            }
            if (isStartupTimedOut() && !startupTimeoutLogged) {
                startupTimeoutLogged = true;
                LOGGER.warn("Nestworld Video startup timed out after 90 seconds: host={}, texture={}", host, textureId);
            }
            return;
        }
        boolean stalled = isPlaying && player.isReady() && !player.isBuffering()
                && lastMediaProgressNanos != 0 && now - lastMediaProgressNanos > 3_000_000_000L;
        long decodedFrames = frameStats.decoded.get();
        long uploadedFrames = frameStats.uploaded.get();
        long decodedDelta = decodedFrames - lastLoggedDecodedFrames;
        long uploadedDelta = uploadedFrames - lastLoggedUploadedFrames;
        lastLoggedDecodedFrames = decodedFrames;
        lastLoggedUploadedFrames = uploadedFrames;
        long lastUploadAgeMs = frameStats.lastUploadNanos == 0 ? -1 : (now - frameStats.lastUploadNanos) / 1_000_000L;
        String host;
        try { host = new URI(url).getHost(); }
        catch (Exception ignored) { host = "unknown"; }
        LOGGER.info("Nestworld Video diagnostic: host={}, state={}, requestedPlaying={}, actualPlaying={}, " +
                        "loading={}, buffering={}, broken={}, stalled={}, media={}ms/{}ms, size={}x{}, " +
                        "texture={}, preRender={}ms, gameFps={}, decodedFrames={} (+{}), uploadedFrames={} (+{}), " +
                        "lastUploadAge={}ms, queuedUploads={}",
                host, player.getStateName(), isPlaying, player.isPlaying(), player.isLoading(), player.isBuffering(),
                isBroken(), stalled, sampledMediaTime, player.isReady() ? player.getDuration() : -1,
                player.width(), player.height(), textureId, lastPreRenderMillis, Minecraft.getInstance().getFps(),
                decodedFrames, decodedDelta, uploadedFrames, uploadedDelta, lastUploadAgeMs,
                Math.max(0, frameStats.submitted.get() - uploadedFrames));
    }

    /** Counts native video-frame callbacks separately from their render-thread texture uploads. */
    private static final class DiagnosticVideoPlayer extends VideoPlayer {
        private final FrameStats stats;

        DiagnosticVideoPlayer(Minecraft minecraft, FrameStats stats) {
            super(task -> {
                // Match Minecraft's render-thread executor while tracking queue and completion separately.
                stats.submitted.incrementAndGet();
                minecraft.execute(() -> {
                    task.run();
                    stats.uploaded.incrementAndGet();
                    stats.lastUploadNanos = System.nanoTime();
                });
            });
            this.stats = stats;
        }

        @Override public void display(MediaPlayer mediaPlayer, ByteBuffer[] buffers, BufferFormat format) {
            stats.decoded.incrementAndGet();
            super.display(mediaPlayer, buffers, format);
        }
    }

    private static final class FrameStats {
        private final AtomicLong decoded = new AtomicLong();
        private final AtomicLong submitted = new AtomicLong();
        private final AtomicLong uploaded = new AtomicLong();
        private volatile long lastUploadNanos;
    }

    public void setPlaying(boolean playing) {
        this.isPlaying = playing;
        applyPendingPause();
    }

    /** Correct local decoder drift without seeking for tiny network/timer differences. */
    public void syncPosition(long positionMs) {
        pendingPositionMs = Math.max(0, positionMs);
        applyPendingSeek();
    }

    public boolean needsInitialSync() {
        return !initialSyncComplete;
    }

    private void applyPendingSeek() {
        if (pendingPositionMs < 0 || !canControlPlayback() || !player.isSeekAble() || player.isBuffering()) return;
        try {
            long current = player.getTime();
            if (current < 0 || Math.abs(current - pendingPositionMs) > SEEK_DRIFT_THRESHOLD_MS) {
                LOGGER.debug("Nestworld Video: seek from {}ms to {}ms, state={}, decodedFrames={}",
                        current, pendingPositionMs, player.getStateName(), frameStats.decoded.get());
                player.seekTo(pendingPositionMs);
            }
            pendingPositionMs = -1;
            initialSyncComplete = true;
        } catch (RuntimeException exception) {
            LOGGER.debug("Nestworld Video: delayed seek failed for {}", url, exception);
        }
    }

    private void applyPendingPause() {
        if (canControlPlayback() && (appliedPlaying == null || appliedPlaying != isPlaying)) {
            if (player.isPlaying() == isPlaying) {
                appliedPlaying = isPlaying;
                return;
            }
            // WaterMedia silently ignores setPauseMode when VLC cannot pause.
            // Keep the request pending until the native state confirms it.
            if (player.raw().mediaPlayer().status().canPause())
                player.setPauseMode(!isPlaying);
        }
    }

    public boolean isPlaying() {
        return isPlaying && player.isPlaying();
    }

    public boolean isLoading() { return player.isLoading(); }
    public boolean isBuffering() { return player.isBuffering(); }
    public boolean isBroken() {
        // WaterMedia reports a null raw player as broken while it is still
        // initializing the VLC component. Treat that as loading until the
        // startup timeout expires; only inspect its error state once raw exists.
        return (player.raw() != null && player.isBroken() && (firstTextureReady || startupRetried))
                || (startupRetried && frameStats.decoded.get() == 0 && player.isEnded())
                || isStartupTimedOut();
    }
    private boolean isStartupTimedOut() {
        return !firstTextureReady && System.nanoTime() - createdAtNanos >= STARTUP_TIMEOUT_NANOS;
    }
    public boolean isEnded() { return player.isEnded(); }
    private boolean endReported;
    /** True exactly once, after a video that actually rendered frames has played to its end. */
    public boolean pollEnded() {
        if (endReported || !isPlaying || !firstTextureReady || frameStats.decoded.get() == 0 || !player.isEnded())
            return false;
        endReported = true;
        return true;
    }
    public long getDuration() { return player.isReady() ? player.getDuration() : -1; }
    /** Current media clock from VLC, which naturally holds during buffering and pause. */
    public long getPlaybackTime() {
        if (!player.isReady()) return -1;
        try {
            long time = player.getTime();
            return time >= 0 ? time : -1;
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    public Dimension getDimensions() {
        if (player.isReady()) {
            return player.dimension();
        }
        return new Dimension(0, 0);
    }
    
    private int baseVolume = 100;

    public void setVolume(int volume) {
        baseVolume = Math.max(0, Math.min(200, volume));
    }

    public int getVolume() {
        return baseVolume;
    }

    public void applySpatialVolume(double factor) {
        int effective = (int) (baseVolume * factor);
        player.setVolume(Math.max(0, Math.min(200, effective)));
    }

    public void release() {
        pendingPositionMs = -1;
        player.stop();
        player.release();
    }
}
