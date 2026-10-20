package com.nestworld.video.ffmpeg;

/** A stream decoder running on its own thread. Implemented by the isolated FFmpeg worker. */
public interface VideoDecoder {
    /** Opens the stream and starts decoding on a background thread; results arrive through the listener. */
    void open(String url, String userAgent, String referer, DecoderListener listener);

    /** Jumps to a position (microseconds from the start). Returns the generation tag of frames that follow. */
    int seek(long positionUs);

    /**
     * Jumps to {@code positionUs} as the container understands it, then drops everything before {@code skipToUs} (for
     * streams whose index is off: aim earlier, land exactly). Returns the generation tag of frames that follow.
     */
    default int seek(long positionUs, long skipToUs) { return seek(positionUs); }

    /** Stops decoding and frees native resources. Safe to call more than once. */
    void close();
}
