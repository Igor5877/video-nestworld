package com.nestworld.video.ffmpeg.impl;

import com.nestworld.video.ffmpeg.DecoderListener;
import com.nestworld.video.ffmpeg.StreamInfo;
import com.nestworld.video.ffmpeg.VideoDecoder;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FFmpeg decoding loop. This class and everything under org.bytedeco are loaded by the isolated
 * class loader created by FfmpegLibs, so nothing here may be referenced from regular mod code.
 */
public final class FfmpegWorker implements VideoDecoder {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final int AUDIO_RATE = 48_000, AUDIO_CHANNELS = 2;

    private volatile boolean closed;
    private long pendingSeekUs = -1;
    private int pendingGeneration, issuedGeneration;
    private Thread thread;

    @Override public void open(String url, String userAgent, String referer, DecoderListener listener) {
        ClassLoader loader = FfmpegWorker.class.getClassLoader();
        thread = new Thread(() -> run(url, userAgent, referer, listener), "nestworld-ffmpeg-" + IDS.incrementAndGet());
        thread.setContextClassLoader(loader);
        thread.setDaemon(true);
        thread.start();
    }

    /** Frames carry the generation that was current when they were decoded, so stale ones can be dropped. */
    @Override public synchronized int seek(long positionUs) {
        pendingSeekUs = Math.max(0, positionUs);
        pendingGeneration = ++issuedGeneration;
        return pendingGeneration;
    }

    @Override public void close() {
        closed = true;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    private void run(String url, String userAgent, String referer, DecoderListener listener) {
        FFmpegFrameGrabber grabber = null;
        try {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR);
            grabber = new FFmpegFrameGrabber(url);
            if (userAgent != null && !userAgent.isEmpty()) grabber.setOption("user_agent", userAgent);
            if (referer != null && !referer.isEmpty()) grabber.setOption("headers", "Referer: " + referer + "\r\n");
            grabber.setOption("rw_timeout", "20000000");          // network stall limit, microseconds
            grabber.setOption("reconnect", "1");
            grabber.setOption("reconnect_streamed", "1");
            grabber.setOption("reconnect_delay_max", "5");
            grabber.setOption("allowed_extensions", "ALL");
            grabber.setPixelFormat(avutil.AV_PIX_FMT_RGBA);
            grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
            grabber.setAudioChannels(AUDIO_CHANNELS);
            grabber.setSampleRate(AUDIO_RATE);
            grabber.start();

            boolean hasAudio = grabber.getAudioChannels() > 0;
            int width = grabber.getImageWidth(), height = grabber.getImageHeight();
            double aspect = grabber.getAspectRatio() > 0 ? grabber.getAspectRatio() : (double) width / Math.max(1, height);
            // A pixel aspect ratio of 1 reports width/height; anamorphic streams report the display ratio.
            if (Math.abs(aspect - 1.0) < 1e-6 && width > 0 && height > 0) aspect = (double) width / height;
            listener.onInfo(new StreamInfo(width, height, aspect, grabber.getFrameRate(),
                    Math.max(0, grabber.getLengthInTime()), hasAudio, AUDIO_RATE, AUDIO_CHANNELS));

            long baseUs = Long.MIN_VALUE;   // timestamp of the first frame: HLS/TS streams rarely start at zero
            ByteBuffer audioBytes = null;
            int gen = 0;
            while (!closed) {
                long seek = -1;
                synchronized (this) {
                    // Timestamps are relative to the first frame, so a seek can only be applied after it.
                    if (pendingSeekUs >= 0 && baseUs != Long.MIN_VALUE) {
                        seek = pendingSeekUs; pendingSeekUs = -1; gen = pendingGeneration;
                    }
                }
                if (seek >= 0) {
                    // setTimestamp() is relative to the container's start_time (which JavaCV adds itself),
                    // while frame timestamps and baseUs are absolute.
                    long start = grabber.getFormatContext().start_time();
                    if (start == avutil.AV_NOPTS_VALUE) start = 0;
                    grabber.setTimestamp(Math.max(0, seek + baseUs - start));
                }
                Frame frame = grabber.grab();
                if (frame == null) { listener.onEnded(gen); break; }
                if (baseUs == Long.MIN_VALUE) baseUs = frame.timestamp;
                long pts = Math.max(0, frame.timestamp - baseUs);
                if (frame.image != null && frame.image.length > 0) {
                    ByteBuffer pixels = (ByteBuffer) frame.image[0];
                    listener.onVideo(pixels, frame.imageWidth, frame.imageHeight, frame.imageStride, pts, gen);
                } else if (frame.samples != null && frame.samples.length > 0 && frame.samples[0] instanceof ShortBuffer shorts) {
                    int bytes = shorts.remaining() * 2;
                    if (audioBytes == null || audioBytes.capacity() < bytes)
                        audioBytes = ByteBuffer.allocateDirect(Math.max(bytes, 16384)).order(ByteOrder.LITTLE_ENDIAN);
                    audioBytes.clear();
                    audioBytes.asShortBuffer().put(shorts.duplicate());
                    audioBytes.limit(bytes);
                    listener.onAudio(audioBytes, pts, gen);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            if (!closed) listener.onError(String.valueOf(t.getMessage()), t);
        } finally {
            if (grabber != null) {
                try { grabber.stop(); } catch (Throwable ignored) { }
                try { grabber.release(); } catch (Throwable ignored) { }
            }
        }
    }
}
