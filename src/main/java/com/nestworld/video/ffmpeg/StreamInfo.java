package com.nestworld.video.ffmpeg;

/** What the decoder learned about a stream once it was opened. Plain data, safe to share across class loaders. */
public final class StreamInfo {
    public final int width, height;
    /** Display aspect ratio (width / height), accounting for non-square pixels. */
    public final double aspectRatio;
    public final double frameRate;
    /** Total length in microseconds, or 0 for live streams. */
    public final long durationUs;
    public final boolean hasAudio;
    public final int sampleRate, channels;

    public StreamInfo(int width, int height, double aspectRatio, double frameRate, long durationUs,
                      boolean hasAudio, int sampleRate, int channels) {
        this.width = width; this.height = height; this.aspectRatio = aspectRatio; this.frameRate = frameRate;
        this.durationUs = durationUs; this.hasAudio = hasAudio; this.sampleRate = sampleRate; this.channels = channels;
    }
}
