#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float Sharpness;
// 0 = bicubic (Catmull-Rom), 1 = edge-adaptive upscaling (EASU-style), 2 = Anime4K-style line sharpening (experimental)
uniform float Upscaler;
// Fraction of the texture that holds the picture (decoders may allocate larger frame buffers).
uniform vec2 UvMax;

in vec2 texCoord0;
out vec4 fragColor;

// ---------------------------------------------------------------------------------------------------------------
// Catmull-Rom bicubic filtering in 5 bilinear taps. Compared with plain bilinear it keeps edges crisp and
// smooths the stair-steps that appear when a low-resolution video is stretched over a large screen.
// ---------------------------------------------------------------------------------------------------------------
vec3 catmullRom(sampler2D tex, vec2 uv) {
    vec2 size = vec2(textureSize(tex, 0));
    vec2 pos = uv * size;
    vec2 center = floor(pos - 0.5) + 0.5;
    vec2 f = pos - center;

    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);

    vec2 w12 = w1 + w2;
    vec2 offset12 = w2 / w12;

    // Never sample beyond the visible picture: the rows/columns after it can hold garbage.
    vec2 lo = vec2(0.5) / size, hi = (UvMax * size - 0.5) / size;
    vec2 p0 = clamp((center - 1.0) / size, lo, hi);
    vec2 p3 = clamp((center + 2.0) / size, lo, hi);
    vec2 p12 = clamp((center + offset12) / size, lo, hi);

    vec3 result = texture(tex, vec2(p12.x, p0.y)).rgb * (w12.x * w0.y)
                + texture(tex, vec2(p0.x, p12.y)).rgb * (w0.x * w12.y)
                + texture(tex, vec2(p12.x, p12.y)).rgb * (w12.x * w12.y)
                + texture(tex, vec2(p3.x, p12.y)).rgb * (w3.x * w12.y)
                + texture(tex, vec2(p12.x, p3.y)).rgb * (w12.x * w3.y);
    float weight = (w12.x * w0.y) + (w0.x * w12.y) + (w12.x * w12.y) + (w3.x * w12.y) + (w12.x * w3.y);
    return clamp(result / weight, 0.0, 1.0);
}

// ---------------------------------------------------------------------------------------------------------------
// Edge-adaptive spatial upscaling in the spirit of AMD FidelityFX FSR 1.0 "EASU" (MIT licence): a 12-tap
// Lanczos-like kernel that is rotated to follow the local edge direction and stretched along it, so diagonal
// lines are reconstructed as lines instead of stair-steps. The result is clamped to the four nearest texels
// to avoid ringing.
// ---------------------------------------------------------------------------------------------------------------
float easuLuma(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }

void easuSet(inout vec2 dir, inout float len, vec2 pp, bool biS, bool biT, bool biU, bool biV,
             float lA, float lB, float lC, float lD, float lE) {
    float w = 0.0;
    if (biS) w = (1.0 - pp.x) * (1.0 - pp.y);
    if (biT) w = pp.x * (1.0 - pp.y);
    if (biU) w = (1.0 - pp.x) * pp.y;
    if (biV) w = pp.x * pp.y;
    float dc = lD - lC, cb = lC - lB;
    float lenX = 1.0 / max(max(abs(dc), abs(cb)), 1.0 / 32768.0);
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
    lenX *= lenX;
    len += lenX * w;
    float ec = lE - lC, ca = lC - lA;
    float lenY = 1.0 / max(max(abs(ec), abs(ca)), 1.0 / 32768.0);
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
    lenY *= lenY;
    len += lenY * w;
}

void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * -dir.y + off.y * dir.x);
    v *= len;
    float d2 = min(v.x * v.x + v.y * v.y, clp);
    // Approximation of lanczos2 without sin() or sqrt().
    float wB = 2.0 / 5.0 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

vec3 easu(sampler2D tex, vec2 uv) {
    ivec2 size = textureSize(tex, 0);
    vec2 pos = uv * vec2(size) - 0.5;
    vec2 fp = floor(pos);
    vec2 pp = pos - fp;
    ivec2 ip = ivec2(fp);
    ivec2 hi = max(ivec2(UvMax * vec2(size)) - 1, ivec2(0));
    //    b c
    //  e f g h
    //  i j k l
    //    n o
    vec3 b = texelFetch(tex, clamp(ip + ivec2( 0, -1), ivec2(0), hi), 0).rgb;
    vec3 c = texelFetch(tex, clamp(ip + ivec2( 1, -1), ivec2(0), hi), 0).rgb;
    vec3 e = texelFetch(tex, clamp(ip + ivec2(-1,  0), ivec2(0), hi), 0).rgb;
    vec3 f = texelFetch(tex, clamp(ip + ivec2( 0,  0), ivec2(0), hi), 0).rgb;
    vec3 g = texelFetch(tex, clamp(ip + ivec2( 1,  0), ivec2(0), hi), 0).rgb;
    vec3 h = texelFetch(tex, clamp(ip + ivec2( 2,  0), ivec2(0), hi), 0).rgb;
    vec3 i = texelFetch(tex, clamp(ip + ivec2(-1,  1), ivec2(0), hi), 0).rgb;
    vec3 j = texelFetch(tex, clamp(ip + ivec2( 0,  1), ivec2(0), hi), 0).rgb;
    vec3 k = texelFetch(tex, clamp(ip + ivec2( 1,  1), ivec2(0), hi), 0).rgb;
    vec3 l = texelFetch(tex, clamp(ip + ivec2( 2,  1), ivec2(0), hi), 0).rgb;
    vec3 n = texelFetch(tex, clamp(ip + ivec2( 0,  2), ivec2(0), hi), 0).rgb;
    vec3 o = texelFetch(tex, clamp(ip + ivec2( 1,  2), ivec2(0), hi), 0).rgb;

    float bL = easuLuma(b), cL = easuLuma(c), eL = easuLuma(e), fL = easuLuma(f), gL = easuLuma(g), hL = easuLuma(h);
    float iL = easuLuma(i), jL = easuLuma(j), kL = easuLuma(k), lL = easuLuma(l), nL = easuLuma(n), oL = easuLuma(o);

    vec2 dir = vec2(0.0);
    float len = 0.0;
    easuSet(dir, len, pp, true, false, false, false, bL, eL, fL, gL, jL);
    easuSet(dir, len, pp, false, true, false, false, cL, fL, gL, hL, kL);
    easuSet(dir, len, pp, false, false, true, false, fL, iL, jL, kL, nL);
    easuSet(dir, len, pp, false, false, false, true, gL, jL, kL, lL, oL);

    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    bool zro = dirR < 1.0 / 32768.0;
    dirR = inversesqrt(max(dirR, 1.0 / 32768.0));
    dirR = zro ? 1.0 : dirR;
    dir.x = zro ? 1.0 : dir.x;
    dir *= vec2(dirR);
    len = len * 0.5;
    len *= len;
    float stretch = (dir.x * dir.x + dir.y * dir.y) / max(max(abs(dir.x), abs(dir.y)), 1.0 / 32768.0);
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = 1.0 / lob;

    vec3 min4 = min(min(f, g), min(j, k));
    vec3 max4 = max(max(f, g), max(j, k));
    vec3 aC = vec3(0.0);
    float aW = 0.0;
    easuTap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, b);
    easuTap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, c);
    easuTap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, i);
    easuTap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, j);
    easuTap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, f);
    easuTap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, e);
    easuTap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, k);
    easuTap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, l);
    easuTap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, h);
    easuTap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, g);
    easuTap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, o);
    easuTap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, n);
    return min(max4, max(min4, aC / aW));
}

// ---------------------------------------------------------------------------------------------------------------
// EXPERIMENTAL "Anime4K-style" mode, single pass. The real Anime4K (MIT licence, bloc97) runs several passes of small
// convolutional networks; that needs off-screen render targets, which a block-entity renderer cannot cheaply have.
// This keeps its core idea, which suits line art: take the edge-directed upscale as a base, then "push" every pixel
// next to a strong edge towards the darkest or brightest colour of its 3x3 source neighbourhood (thinner, crisper
// lines and flat colour areas without halos) and slightly deepen dark lines.
// ---------------------------------------------------------------------------------------------------------------
float a4kLuma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

vec3 anime4k(sampler2D tex, vec2 uv, vec3 base, float strength) {
    ivec2 size = textureSize(tex, 0);
    ivec2 hi = max(ivec2(UvMax * vec2(size)) - 1, ivec2(0));
    ivec2 ip = clamp(ivec2(floor(uv * vec2(size))), ivec2(0), hi);
    vec3 cMin = base, cMax = base;
    float lMin = a4kLuma(base), lMax = lMin, lSum = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            vec3 c = texelFetch(tex, clamp(ip + ivec2(dx, dy), ivec2(0), hi), 0).rgb;
            float l = a4kLuma(c);
            lSum += l;
            if (l < lMin) { lMin = l; cMin = c; }
            if (l > lMax) { lMax = l; cMax = c; }
        }
    }
    float lMean = lSum / 9.0;
    float range = lMax - lMin;
    float edge = smoothstep(0.06, 0.30, range);
    float side = (a4kLuma(base) - lMean) / max(range, 0.001);        // -1 .. 1: which side of the edge this pixel is on
    vec3 target = side > 0.0 ? cMax : cMin;
    float push = clamp(abs(side) * 1.6, 0.0, 1.0) * edge * strength;
    vec3 result = mix(base, target, push * 0.6);
    result *= 1.0 - 0.12 * edge * step(side, -0.2) * strength;       // deepen the dark side of a line
    return clamp(result, 0.0, 1.0);
}

void main() {
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 dx = dFdx(texCoord0), dy = dFdy(texCoord0);
    // How many video texels one screen pixel covers: below 1 the picture is being enlarged.
    vec2 texelsPerPixel = max(abs(dx), abs(dy)) * size;
    float magnification = max(texelsPerPixel.x, texelsPerPixel.y);

    vec3 color;
    if (magnification >= 1.0) {
        // Shrinking: let the hardware pick the mip level and filter anisotropically.
        vec2 safeUv = clamp(texCoord0, vec2(0.5) / size, (UvMax * size - 0.5) / size);
        color = textureGrad(Sampler0, safeUv, dx, dy).rgb;
    } else if (Upscaler > 1.5) {
        color = anime4k(Sampler0, texCoord0, easu(Sampler0, texCoord0), clamp(0.6 + Sharpness * 0.8, 0.0, 1.4));
    } else if (Upscaler > 0.5) {
        color = easu(Sampler0, texCoord0);
    } else {
        color = catmullRom(Sampler0, texCoord0);
    }
    if (Sharpness > 0.0 && Upscaler < 1.5) {
        // Unsharp mask. Enlarging: against the four neighbouring texels. Shrinking: against what one screen pixel
        // covers, so the slider also sharpens a video that is shown smaller than it is.
        vec3 blur;
        if (magnification < 1.0) {
            vec2 px = 1.0 / size;
            blur = (texture(Sampler0, texCoord0 + vec2(px.x, 0.0)).rgb + texture(Sampler0, texCoord0 - vec2(px.x, 0.0)).rgb
                  + texture(Sampler0, texCoord0 + vec2(0.0, px.y)).rgb + texture(Sampler0, texCoord0 - vec2(0.0, px.y)).rgb) * 0.25;
        } else {
            vec2 hi = (UvMax * size - 0.5) / size, lo = vec2(0.5) / size;
            blur = (textureGrad(Sampler0, clamp(texCoord0 + dx, lo, hi), dx, dy).rgb + textureGrad(Sampler0, clamp(texCoord0 - dx, lo, hi), dx, dy).rgb
                  + textureGrad(Sampler0, clamp(texCoord0 + dy, lo, hi), dx, dy).rgb + textureGrad(Sampler0, clamp(texCoord0 - dy, lo, hi), dx, dy).rgb) * 0.25;
        }
        color = clamp(color + (color - blur) * Sharpness * 1.5, 0.0, 1.0);
    }
    fragColor = vec4(color, 1.0) * ColorModulator;
}
