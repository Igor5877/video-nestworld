package com.nestworld.video.client;

// This class is kept for potential future use (e.g. GUI video preview).
// The block entity renderer (VideoScreenBlockRenderer) uses WaterMedia's GL texture
// ID directly via Display.getTextureId(), which is the correct approach for VLC-backed
// video: preRender() returns an OpenGL texture handle, not a BufferedImage.
public class VideoRenderer {
    private VideoRenderer() {}
}
