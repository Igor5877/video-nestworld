package com.nestworld.video.ffmpeg;

import java.nio.ByteBuffer;

/**
 * Receives decoded media on the decoder thread. Only JDK types cross the class-loader boundary.
 * Buffers are reused by the decoder: copy what you need before returning. Blocking inside a callback
 * applies back-pressure to the decoder.
 */
public interface DecoderListener {
    void onInfo(StreamInfo info);

    /** @param rgba tightly packed RGBA, {@code stride} bytes per row; ptsUs is relative to the start of the stream */
    void onVideo(ByteBuffer rgba, int width, int height, int stride, long ptsUs, int generation) throws InterruptedException;

    /** @param pcm interleaved signed 16-bit little-endian samples at the rate announced in {@link StreamInfo} */
    void onAudio(ByteBuffer pcm, long ptsUs, int generation) throws InterruptedException;

    void onEnded(int generation);

    void onError(String message, Throwable cause);
}
