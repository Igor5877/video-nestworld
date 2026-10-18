package com.nestworld.video.ffmpeg;

/** A stream decoder running on its own thread. Implemented by the isolated FFmpeg worker. */
public interface VideoDecoder {
    /** Opens the stream and starts decoding on a background thread; results arrive through the listener. */
    void open(String url, String userAgent, String referer, DecoderListener listener);

    /** Jumps to a position (microseconds from the start). Returns the generation tag of frames that follow. */
    int seek(long positionUs);

    /** Stops decoding and frees native resources. Safe to call more than once. */
    void close();
}
