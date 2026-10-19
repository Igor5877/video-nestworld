package com.nestworld.video.client.acoustics;

/** Minimal immutable 3D vector; the acoustic maths stays free of Minecraft types so it can be unit tested. */
public record V3(double x, double y, double z) {
    public V3 add(V3 o) { return new V3(x + o.x, y + o.y, z + o.z); }
    public V3 sub(V3 o) { return new V3(x - o.x, y - o.y, z - o.z); }
    public V3 scale(double k) { return new V3(x * k, y * k, z * k); }
    public double dot(V3 o) { return x * o.x + y * o.y + z * o.z; }
    public V3 cross(V3 o) { return new V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x); }
    public double length() { return Math.sqrt(dot(this)); }
    public double distanceTo(V3 o) { return sub(o).length(); }
    public V3 normalized() { double l = length(); return l < 1e-9 ? new V3(0, 1, 0) : scale(1 / l); }
    /** Mirror of this direction on a surface with the given unit normal. */
    public V3 reflect(V3 normal) { return sub(normal.scale(2 * dot(normal))); }
}
