package com.nestworld.video.client.util;

import com.mojang.logging.LogUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Streams decoded PCM through Minecraft's own OpenAL context and exposes the playback position as the
 * master clock for video. Runs its own thread; all OpenAL calls happen there.
 *
 * <p>In spatial mode the sound comes from the screen itself: stereo is split into a left and a right
 * source at the screen's edges, mono plays from its centre. Distance falloff stays under our control
 * (rolloff 0 plus a gain factor), so only the direction is handled by OpenAL.
 *
 * <p>A point source to one side goes entirely into one ear (OpenAL pans hard once a source is past the
 * speakers' angle), which is not how a wide screen in a room sounds. So part of the sound plays as is,
 * from inside the listener's head (the plain stereo track), and only the rest comes from the screen:
 * turned sideways the near ear is clearly louder, the far one still hears it.
 */
final class AlAudio implements Runnable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BUFFER_COUNT = 16;
    private static final long MAX_QUEUED_US = 8_000_000L;
    /** One AL buffer holds about this much audio; decoder frames are only ~20 ms. */
    private static final long CHUNK_US = 100_000L;

    private record Chunk(ByteBuffer pcm, long ptsUs, int generation) {}

    private final int sampleRate, channels, alFormat, bytesPerFrame;
    private final boolean spatial;
    /** Share of the opposite channel mixed into each speaker, 0 (hard left/right) to 0.5 (mono). */
    private final float crossfeed;
    private final int sourceCount;
    /** Spatial mode: the sources at the screen (1 or 2), then one head-relative source with the plain track. */
    private final int placedCount;
    /** Share of the plain (head-relative) track in spatial mode, from the softness setting. */
    private final float ambientShare;
    private final Object lock = new Object();
    private final ArrayDeque<Chunk> queue = new ArrayDeque<>();
    private long queuedUs;
    private volatile int expectedGeneration;
    private volatile boolean closed, failed, playing, started;
    private volatile float gain = 1f;
    private volatile boolean flushRequested;
    private volatile long dropBeforeUs = Long.MIN_VALUE;
    /** Speaker positions in world space: {leftX, leftY, leftZ, rightX, rightY, rightZ} (right = left for mono). */
    private volatile float[] speakers = {0, 0, 0, 0, 0, 0};
    /** Room acoustics from the client thread (null = none); applied through OpenAL EFX by the audio thread. */
    private volatile com.nestworld.video.client.acoustics.AcousticResult acoustics;

    // Published by the audio thread.
    private volatile long clockUs = -1;
    private volatile long clockStampNanos;
    private volatile boolean alPlaying;
    private volatile int alQueuedBuffers;
    /** Delay between what OpenAL has mixed and what comes out of the speakers (the output device's buffer), -1 = unknown. */
    private volatile long outputLatencyUs = -1;

    AlAudio(int sampleRate, int channels, boolean spatial, float crossfeed) {
        this.crossfeed = Math.max(0f, Math.min(0.5f, crossfeed));
        this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        this.spatial = spatial;
        this.placedCount = spatial && this.channels == 2 ? 2 : 1;
        this.sourceCount = spatial ? placedCount + 1 : 1;
        this.ambientShare = 0.15f + 0.8f * this.crossfeed;   // softness 0..100 -> 15..55 %
        // Spatial sources are mono; otherwise the stereo stream plays as is, relative to the listener.
        this.alFormat = this.channels == 1 || spatial ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        this.bytesPerFrame = 2 * this.channels;
        if (ALC10.alcGetCurrentContext() == 0L) {
            failed = true;
            LOGGER.info("Nestworld Video: no OpenAL context yet, playing without sound clock");
            return;
        }
        Thread thread = new Thread(this, "nestworld-video-audio");
        thread.setDaemon(true);
        thread.start();
    }

    boolean isAvailable() { return !failed && !closed; }
    boolean isPlaying() { return alPlaying; }
    boolean isDrained() {
        synchronized (lock) { return queue.isEmpty() && alQueuedBuffers == 0; }
    }
    /** Output device latency in microseconds as reported by AL_SOFT_source_latency, or -1 when it cannot be measured. */
    long outputLatencyUs() { return outputLatencyUs; }
    long queuedUs() { synchronized (lock) { return queuedUs; } }
    /** Audio the listener still has ahead of it: waiting for the audio thread plus already handed to OpenAL. */
    long bufferedUs() { return queuedUs() + alQueuedBuffers * CHUNK_US; }
    /** How many times the output ran completely dry while it should have been playing. */
    int underruns() { return underruns; }
    private volatile int underruns;
    private boolean wasDry;
    /** Up to {@value #MAX_GAIN}: the menu's volume goes to 200%. */
    void setGain(float value) { gain = Math.max(0f, Math.min(MAX_GAIN, value)); }
    private static final float MAX_GAIN = 2f;
    /** Playback speed (OpenAL pitch): the clock counts played samples, so it follows by itself. */
    private volatile float pitch = 1f;
    void setPitch(float value) { pitch = Math.max(0.9f, Math.min(1.1f, value)); }
    void setPlaying(boolean value) { playing = value; }
    void setAcoustics(com.nestworld.video.client.acoustics.AcousticResult value) { acoustics = value; }

    boolean isSpatial() { return spatial; }

    /** Places the sound: the centre of the screen and the vector from the centre to its right-hand speaker. */
    void setPlacement(double cx, double cy, double cz, double rx, double ry, double rz) {
        speakers = new float[]{(float) (cx - rx), (float) (cy - ry), (float) (cz - rz),
                (float) (cx + rx), (float) (cy + ry), (float) (cz + rz)};
    }

    /** Begins playback at the given media time; earlier audio is discarded. */
    void start(long startPtsUs) {
        dropBeforeUs = startPtsUs;
        started = true;
    }

    /** Position of the media clock in microseconds, or -1 before anything played. */
    long clockUs() {
        long base = clockUs;
        if (base < 0) return -1;
        if (alPlaying) {
            long extra = (System.nanoTime() - clockStampNanos) / 1000L;
            return base + (long) (Math.max(0, Math.min(extra, 30_000L)) * pitch);
        }
        return base;
    }

    /** Blocks while too much audio is buffered; returns false when the chunk was dropped. */
    boolean offer(ByteBuffer pcm, long ptsUs, int generation) throws InterruptedException {
        synchronized (lock) {
            while (!closed && !failed && queuedUs > MAX_QUEUED_US && generation >= expectedGeneration) lock.wait(50);
            if (closed || failed || generation < expectedGeneration) return false;
            ByteBuffer copy = ByteBuffer.allocateDirect(pcm.remaining()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            copy.put(pcm.duplicate()).flip();
            queue.add(new Chunk(copy, ptsUs, generation));
            queuedUs += copy.remaining() * 1_000_000L / ((long) sampleRate * bytesPerFrame);
            return true;
        }
    }

    /** Drops buffered chunks that end before {@code ptsUs} (while the video waits to start, so the decoder is not blocked). */
    void discardBefore(long ptsUs) {
        synchronized (lock) {
            while (!queue.isEmpty()) {
                Chunk c = queue.peek();
                long durationUs = c.pcm.remaining() * 1_000_000L / ((long) sampleRate * bytesPerFrame);
                if (c.ptsUs + durationUs >= ptsUs) break;
                queue.poll(); queuedUs -= durationUs;
            }
            lock.notifyAll();
        }
    }

    /** Drops everything buffered and ignores chunks older than the new generation. */
    void flush(int newGeneration) {
        synchronized (lock) {
            expectedGeneration = newGeneration;
            queue.clear();
            queuedUs = 0;
            lock.notifyAll();
        }
        started = false;
        clockUs = -1;
        flushRequested = true;
    }

    void close() {
        closed = true;
        synchronized (lock) { lock.notifyAll(); }
    }

    // ---- audio thread ------------------------------------------------------------------------------------

    @Override public void run() {
        long context = ALC10.alcGetCurrentContext();
        int[] sources = new int[sourceCount];
        int[][] buffers = new int[sourceCount][BUFFER_COUNT];
        long[] bufferFrames = new long[BUFFER_COUNT];
        List<Integer> free = new ArrayList<>();
        AlEfx efx = null;
        try {
            for (int s = 0; s < sourceCount; s++) {
                sources[s] = AL10.alGenSources();
                AL10.alSourcef(sources[s], AL10.AL_MAX_GAIN, MAX_GAIN);   // OpenAL caps a source at 1 unless told otherwise
                for (int i = 0; i < BUFFER_COUNT; i++) buffers[s][i] = AL10.alGenBuffers();
                if (spatial && s < placedCount) {
                    AL10.alSourcei(sources[s], AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
                    AL10.alSourcef(sources[s], AL10.AL_ROLLOFF_FACTOR, 0f);   // falloff is applied through the gain
                } else {
                    AL10.alSourcei(sources[s], AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
                    AL10.alSource3f(sources[s], AL10.AL_POSITION, 0f, 0f, 0f);
                }
            }
            for (int i = 0; i < BUFFER_COUNT; i++) free.add(i);
            check("init");
            efx = com.nestworld.video.NestworldConfig.ACOUSTICS.get() ? AlEfx.create(context, sources) : null;
            long lastNanos = System.nanoTime();
            boolean latencyExtension = AL10.alIsExtensionPresent("AL_SOFT_source_latency");
            long lastLatencyNanos = 0;
            long unqueuedFrames = 0, baseUs = -1;
            int queued = 0;
            float smoothGain = -1f, sentGain = -1f, sentPitch = 1f;
            float[] smoothSpeakers = null, sentSpeakers = null;
            while (!closed) {
                if (ALC10.alcGetCurrentContext() != context) throw new IllegalStateException("OpenAL context changed");
                if (flushRequested) {
                    flushRequested = false;
                    for (int source : sources) AL10.alSourceStop(source);
                    for (int s = 0; s < sourceCount; s++) {
                        int pending = Math.max(AL10.alGetSourcei(sources[s], AL10.AL_BUFFERS_PROCESSED), queued);
                        for (int i = 0; i < pending; i++) {
                            try { AL10.alSourceUnqueueBuffers(sources[s]); } catch (Throwable ignored) { break; }
                        }
                        AL10.alSourcei(sources[s], AL10.AL_BUFFER, 0);
                    }
                    free.clear();
                    for (int i = 0; i < BUFFER_COUNT; i++) free.add(i);
                    unqueuedFrames = 0; baseUs = -1; queued = 0; alQueuedBuffers = 0;
                    alPlaying = false;
                }
                // Return finished buffers (the sources are started together; follow the slowest).
                int processed = Integer.MAX_VALUE;
                for (int source : sources) processed = Math.min(processed, AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED));
                for (int i = 0; i < processed; i++) {
                    int id = AL10.alSourceUnqueueBuffers(sources[0]);
                    for (int s = 1; s < sourceCount; s++) AL10.alSourceUnqueueBuffers(sources[s]);
                    for (int b = 0; b < BUFFER_COUNT; b++) if (buffers[0][b] == id) { unqueuedFrames += bufferFrames[b]; free.add(b); }
                    queued--;
                }
                // Fill free buffers with ~100 ms chunks once playback has been started.
                while (started && !free.isEmpty()) {
                    ByteBuffer chunk = nextChunk();
                    if (chunk == null) break;
                    if (baseUs < 0) baseUs = chunkStartUs;
                    int slot = free.remove(free.size() - 1);
                    ByteBuffer[] channelData = split(chunk);
                    bufferFrames[slot] = chunk.remaining() / bytesPerFrame;
                    for (int s = 0; s < sourceCount; s++) {
                        AL10.alBufferData(buffers[s][slot], formatOf(s), channelData[s], sampleRate);
                        AL10.alSourceQueueBuffers(sources[s], buffers[s][slot]);
                    }
                    queued++;
                }
                alQueuedBuffers = queued;
                int state = AL10.alGetSourcei(sources[0], AL10.AL_SOURCE_STATE);
                if (playing && queued > 0 && state != AL10.AL_PLAYING) AL10.alSourcePlayv(sources);
                else if (!playing && state == AL10.AL_PLAYING) AL10.alSourcePausev(sources);
                // Follow the targets gradually: the game tells us about the listener 20 times a second and applying
                // each jump directly sounds like crackling and abrupt panning.
                float targetGain = gain;
                smoothGain = smoothGain < 0 ? targetGain : smoothGain + (targetGain - smoothGain) * 0.06f;
                if (Math.abs(smoothGain - sentGain) > 0.002f || (targetGain == 0f && sentGain != 0f)) {
                    sentGain = Math.abs(smoothGain - targetGain) < 0.002f ? targetGain : smoothGain;
                    for (int s = 0; s < sourceCount; s++)
                        AL10.alSourcef(sources[s], AL10.AL_GAIN, !spatial ? sentGain : s < placedCount ? sentGain * (1f - ambientShare) : sentGain * ambientShare);
                }
                if (pitch != sentPitch) {
                    sentPitch = pitch;
                    for (int source : sources) AL10.alSourcef(source, AL10.AL_PITCH, sentPitch);
                }
                if (spatial) {
                    float[] target = speakers;
                    if (smoothSpeakers == null) smoothSpeakers = target.clone();
                    else for (int i = 0; i < smoothSpeakers.length; i++) smoothSpeakers[i] += (target[i] - smoothSpeakers[i]) * 0.08f;
                    // Only tell OpenAL when a position really moved: every update makes its mixer recompute the source.
                    for (int s = 0; s < placedCount; s++) {
                        boolean moved = sentSpeakers == null;
                        for (int k = 0; k < 3 && !moved; k++) moved = Math.abs(smoothSpeakers[3 * s + k] - sentSpeakers[3 * s + k]) > 0.002f;
                        if (moved) AL10.alSource3f(sources[s], AL10.AL_POSITION, smoothSpeakers[3 * s], smoothSpeakers[3 * s + 1], smoothSpeakers[3 * s + 2]);
                    }
                    if (sentSpeakers == null || sentSpeakers.length != smoothSpeakers.length) sentSpeakers = new float[smoothSpeakers.length];
                    System.arraycopy(smoothSpeakers, 0, sentSpeakers, 0, smoothSpeakers.length);
                }
                if (latencyExtension && alPlaying && System.nanoTime() - lastLatencyNanos > 1_000_000_000L) {
                    lastLatencyNanos = System.nanoTime();
                    double[] offsets = new double[2];
                    org.lwjgl.openal.SOFTSourceLatency.alGetSourcedvSOFT(sources[0], org.lwjgl.openal.SOFTSourceLatency.AL_SEC_OFFSET_LATENCY_SOFT, offsets);
                    if (Double.isFinite(offsets[1]) && offsets[1] >= 0 && offsets[1] < 5.0) outputLatencyUs = (long) (offsets[1] * 1e6);
                }
                if (efx != null) {
                    long nowNanos = System.nanoTime();
                    efx.update(acoustics, (nowNanos - lastNanos) / 1e9f);
                    lastNanos = nowNanos;
                }
                state = AL10.alGetSourcei(sources[0], AL10.AL_SOURCE_STATE);
                alPlaying = state == AL10.AL_PLAYING;
                boolean dry = playing && baseUs >= 0 && queued == 0 && !alPlaying;
                if (dry && !wasDry) underruns++;
                wasDry = dry;
                if (baseUs >= 0) {
                    long played = unqueuedFrames + AL10.alGetSourcei(sources[0], AL11.AL_SAMPLE_OFFSET);
                    clockStampNanos = System.nanoTime();
                    clockUs = baseUs + played * 1_000_000L / sampleRate;
                }
                // The error flag belongs to the context, which Minecraft's sound thread shares: an error there can show up
                // here. Only a run of errors means our own sources are broken.
                int error = AL10.alGetError();
                if (error == AL10.AL_NO_ERROR) loopErrors = 0;
                else if (++loopErrors >= 20) throw new IllegalStateException("OpenAL error 0x" + Integer.toHexString(error) + " in loop");
                try { Thread.sleep(5); } catch (InterruptedException e) { break; }
            }
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("Nestworld Video: audio output stopped, continuing without sound clock: {}", t.toString());
        } finally {
            alPlaying = false;
            try {
                if (ALC10.alcGetCurrentContext() == context) {
                    if (efx != null) efx.close();
                    for (int s = 0; s < sourceCount; s++) {
                        if (sources[s] == 0) continue;
                        AL10.alSourceStop(sources[s]);
                        AL10.alSourcei(sources[s], AL10.AL_BUFFER, 0);
                        AL10.alDeleteSources(sources[s]);
                        for (int b : buffers[s]) if (b != 0) AL10.alDeleteBuffers(b);
                    }
                }
            } catch (Throwable ignored) { /* the sound engine may already be gone */ }
        }
    }

    /** Turns interleaved PCM into what the sources expect: one buffer per source (left/right, or the input as is). */
    private ByteBuffer[] split(ByteBuffer interleaved) {
        if (!spatial) return new ByteBuffer[]{interleaved};
        if (channels == 1) return new ByteBuffer[]{interleaved, interleaved};
        int frames = interleaved.remaining() / 4;
        ByteBuffer left = ByteBuffer.allocateDirect(frames * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        ByteBuffer right = ByteBuffer.allocateDirect(frames * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        ByteBuffer in = interleaved.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float keep = 1f - crossfeed;
        for (int f = 0; f < frames; f++) {
            short l = in.getShort(), r = in.getShort();
            left.putShort((short) Math.round(l * keep + r * crossfeed));
            right.putShort((short) Math.round(r * keep + l * crossfeed));
        }
        left.flip(); right.flip();
        return new ByteBuffer[]{left, right, interleaved};
    }

    /** The screen's sources are mono; the head-relative one plays the track as it is. */
    private int formatOf(int source) {
        if (!spatial || source < placedCount) return alFormat;
        return channels == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
    }

    private long chunkStartUs;
    private int loopErrors;

    /** Merges queued decoder frames into one ~100 ms buffer; returns null when nothing usable is queued. */
    private ByteBuffer nextChunk() {
        ByteBuffer merged = null;
        long target = CHUNK_US * sampleRate / 1_000_000L * bytesPerFrame;
        synchronized (lock) {
            while (!queue.isEmpty() && (merged == null || merged.position() < target)) {
                Chunk c = queue.peek();
                long durationUs = c.pcm.remaining() * 1_000_000L / ((long) sampleRate * bytesPerFrame);
                if (c.generation < expectedGeneration || c.ptsUs + durationUs < dropBeforeUs) {
                    queue.poll(); queuedUs -= durationUs; lock.notifyAll(); continue;
                }
                if (merged == null) {
                    merged = ByteBuffer.allocateDirect((int) Math.max(target * 2, c.pcm.remaining()))
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    chunkStartUs = Math.max(c.ptsUs, dropBeforeUs == Long.MIN_VALUE ? c.ptsUs : dropBeforeUs);
                }
                if (merged.remaining() < c.pcm.remaining()) break;
                queue.poll(); queuedUs -= durationUs; lock.notifyAll();
                merged.put(c.pcm);
            }
        }
        if (merged == null) return null;
        merged.flip();
        return merged;
    }

    private void check(String where) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) throw new IllegalStateException("OpenAL error 0x" + Integer.toHexString(error) + " in " + where);
    }
}
