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
 */
final class AlAudio implements Runnable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BUFFER_COUNT = 16;
    private static final long MAX_QUEUED_US = 8_000_000L;
    /** One AL buffer holds about this much audio; decoder frames are only ~20 ms. */
    private static final long CHUNK_US = 100_000L;

    private record Chunk(ByteBuffer pcm, long ptsUs, int generation) {}

    private final int sampleRate, channels, alFormat, bytesPerFrame;
    private final Object lock = new Object();
    private final ArrayDeque<Chunk> queue = new ArrayDeque<>();
    private long queuedUs;
    private volatile int expectedGeneration;
    private volatile boolean closed, failed, playing, started;
    private volatile float gain = 1f;
    private volatile boolean flushRequested;
    private volatile long dropBeforeUs = Long.MIN_VALUE;

    // Published by the audio thread.
    private volatile long clockUs = -1;
    private volatile long clockStampNanos;
    private volatile boolean alPlaying;
    private volatile int alQueuedBuffers;

    AlAudio(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        this.alFormat = this.channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
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
    long queuedUs() { synchronized (lock) { return queuedUs; } }
    void setGain(float value) { gain = Math.max(0f, Math.min(1f, value)); }
    void setPlaying(boolean value) { playing = value; }

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
            return base + Math.max(0, Math.min(extra, 30_000L));
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
        int source = 0;
        int[] buffers = new int[BUFFER_COUNT];
        long[] bufferFrames = new long[BUFFER_COUNT];
        List<Integer> free = new ArrayList<>();
        try {
            source = AL10.alGenSources();
            for (int i = 0; i < BUFFER_COUNT; i++) { buffers[i] = AL10.alGenBuffers(); free.add(i); }
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);
            check("init");
            long unqueuedFrames = 0, baseUs = -1;
            int queued = 0;
            float appliedGain = -1f;
            while (!closed) {
                if (ALC10.alcGetCurrentContext() != context) throw new IllegalStateException("OpenAL context changed");
                if (flushRequested) {
                    flushRequested = false;
                    AL10.alSourceStop(source);
                    int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
                    for (int i = 0; i < Math.max(processed, queued); i++) {
                        try { AL10.alSourceUnqueueBuffers(source); } catch (Throwable ignored) { break; }
                    }
                    AL10.alSourcei(source, AL10.AL_BUFFER, 0);
                    free.clear();
                    for (int i = 0; i < BUFFER_COUNT; i++) free.add(i);
                    unqueuedFrames = 0; baseUs = -1; queued = 0; alQueuedBuffers = 0;
                    alPlaying = false;
                }
                // Return finished buffers.
                int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
                for (int i = 0; i < processed; i++) {
                    int id = AL10.alSourceUnqueueBuffers(source);
                    for (int b = 0; b < BUFFER_COUNT; b++) if (buffers[b] == id) { unqueuedFrames += bufferFrames[b]; free.add(b); }
                    queued--;
                }
                // Fill free buffers with ~100 ms chunks once playback has been started.
                while (started && !free.isEmpty()) {
                    ByteBuffer chunk = nextChunk();
                    if (chunk == null) break;
                    if (baseUs < 0) baseUs = chunkStartUs;
                    int slot = free.remove(free.size() - 1);
                    bufferFrames[slot] = chunk.remaining() / bytesPerFrame;
                    AL10.alBufferData(buffers[slot], alFormat, chunk, sampleRate);
                    AL10.alSourceQueueBuffers(source, buffers[slot]);
                    queued++;
                }
                alQueuedBuffers = queued;
                int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                if (playing && queued > 0 && state != AL10.AL_PLAYING) AL10.alSourcePlay(source);
                else if (!playing && state == AL10.AL_PLAYING) AL10.alSourcePause(source);
                if (gain != appliedGain) { appliedGain = gain; AL10.alSourcef(source, AL10.AL_GAIN, appliedGain); }
                state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                alPlaying = state == AL10.AL_PLAYING;
                if (baseUs >= 0) {
                    long played = unqueuedFrames + AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
                    clockStampNanos = System.nanoTime();
                    clockUs = baseUs + played * 1_000_000L / sampleRate;
                }
                check("loop");
                try { Thread.sleep(5); } catch (InterruptedException e) { break; }
            }
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("Nestworld Video: audio output stopped, continuing without sound clock: {}", t.toString());
        } finally {
            alPlaying = false;
            try {
                if (source != 0 && ALC10.alcGetCurrentContext() == context) {
                    AL10.alSourceStop(source);
                    AL10.alSourcei(source, AL10.AL_BUFFER, 0);
                    AL10.alDeleteSources(source);
                    for (int b : buffers) if (b != 0) AL10.alDeleteBuffers(b);
                }
            } catch (Throwable ignored) { /* the sound engine may already be gone */ }
        }
    }

    private long chunkStartUs;

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
