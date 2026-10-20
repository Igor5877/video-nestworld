package com.nestworld.video.client.glow;

/** A coarse picture of the current video frame: {@code cols x rows} cells of average colour (r, g, b in 0..1), row 0 at the top. */
public record GlowGrid(int cols, int rows, float[] rgb) {
    public boolean isValid() { return cols > 0 && rows > 0 && rgb != null && rgb.length == cols * rows * 3; }
}
