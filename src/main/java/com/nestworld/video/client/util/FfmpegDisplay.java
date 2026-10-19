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

    private static final class VFrame {
        ByteBuffer data; int width, height, generation; long ptsUs;
    }

    private final String url;
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

    // Client-thread state.
    private int textureId, textureWidth, textureHeight;
    private boolean frameShown, started, playing = true, audioClock, endReported, firstFrameLogged;
    private boolean initialSyncDone;
    private boolean mipmapped;
    private VFrame previewed;
    private long previewedPtsUs = -1;
    private long pendingSyncUs = -1, seekTargetUs = -1;
    private long startedWallNanos, mediaBaseUs, frozenUs, lastMediaUs, lastShownPtsUs;
    private long firstFrameWaitNanos, lastDiagnosticNanos;
    private int baseVolume = 100;
    private double spatial = 1.0;

    public FfmpegDisplay(String url) {
        this.url = url;
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
            String referer = origin(url);
            String target = HlsVariants.pick(url, NestworldConfig.MAX_VIDEO_HEIGHT.get(), USER_AGENT, referer);
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
            if (streamInfo.hasAudio)
                audio = new AlAudio(streamInfo.sampleRate, streamInfo.channels, NestworldConfig.SPATIAL_AUDIO.get(),
                        NestworldConfig.SPATIAL_SOFTNESS.get() / 200f);
            info = streamInfo;
        }

        @Override public void onVideo(ByteBuffer rgba, int width, int height, int stride, long ptsUs, int generation)
                throws InterruptedException {
            VFrame frame;
            synchronized (lock) {
                while (!closed && frames.size() >= maxFrames && generation >= expectedGeneration) lock.wait(50);
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
                for (int row = 0; row < height; row++) {
                    src.position(row * stride).limit(row * stride + width * 4);
                    dst.put(src);
                }
            }
            dst.flip();
            frame.width = width; frame.height = height; frame.ptsUs = ptsUs; frame.generation = generation;
            synchronized (lock) {
                if (closed || generation < expectedGeneration) { pool.add(frame); return; }
                frames.add(frame);
                lock.notifyAll();
            }
        }

        @Override public void onAudio(ByteBuffer pcm, long ptsUs, int generation) throws InterruptedException {
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

    @Override public int getTextureId() {
        StreamInfo streamInfo = info;
        if (streamInfo == null || closed) return -1;
        maybeStart();
        if (started) {
            long media = mediaTimeUs();
            VFrame show = null;
            synchronized (lock) {
                while (!frames.isEmpty() && frames.peek().ptsUs <= media) {
                    if (show != null) pool.add(show);
                    show = frames.poll();
                }
                lock.notifyAll();
            }
            if (show != null) {
                upload(show);
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
        lastMediaUs = mediaBaseUs + (System.nanoTime() - startedWallNanos) / 1000L;
        return lastMediaUs;
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
        frameShown = true;
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            LOGGER.info("Nestworld Video: stream {} decodes at {}x{} (FFmpeg)", safe(url), frame.width, frame.height);
        }
    }

    @Override public void tick() {
        if (closed) return;
        StreamInfo streamInfo = info;
        if (streamInfo != null && pendingSyncUs >= 0 && decoder != null) applySync();
        diagnose();
    }

    private void applySync() {
        long target = pendingSyncUs;
        pendingSyncUs = -1;
        initialSyncDone = true;
        VFrame head;
        synchronized (lock) { head = frames.peek(); }
        long current = started ? mediaTimeUs() : head != null ? head.ptsUs : 0;
        if (Math.abs(current - target) <= SEEK_DRIFT_US) return;
        seekTargetUs = target;
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

    @Override public boolean isEnded() {
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
