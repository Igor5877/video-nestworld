#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float Sharpness;

in vec2 texCoord0;
out vec4 fragColor;

// Catmull-Rom bicubic filtering in 5 bilinear taps. Compared with plain bilinear it keeps
// edges crisp and smooths the stair-steps that appear when a low-resolution video is
// stretched over a large screen.
vec4 catmullRom(sampler2D tex, vec2 uv) {
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

    vec2 p0 = (center - 1.0) / size;
    vec2 p3 = (center + 2.0) / size;
    vec2 p12 = (center + offset12) / size;

    vec4 result = texture(tex, vec2(p12.x, p0.y)) * (w12.x * w0.y)
                + texture(tex, vec2(p0.x, p12.y)) * (w0.x * w12.y)
                + texture(tex, vec2(p12.x, p12.y)) * (w12.x * w12.y)
                + texture(tex, vec2(p3.x, p12.y)) * (w3.x * w12.y)
                + texture(tex, vec2(p12.x, p3.y)) * (w12.x * w3.y);
    float weight = (w12.x * w0.y) + (w0.x * w12.y) + (w12.x * w12.y) + (w3.x * w12.y) + (w12.x * w3.y);
    return clamp(result / weight, 0.0, 1.0);
}

void main() {
    vec3 color = catmullRom(Sampler0, texCoord0).rgb;
    if (Sharpness > 0.0) {
        // Unsharp mask against the four neighbouring texels.
        vec2 px = 1.0 / vec2(textureSize(Sampler0, 0));
        vec3 blur = (texture(Sampler0, texCoord0 + vec2(px.x, 0.0)).rgb + texture(Sampler0, texCoord0 - vec2(px.x, 0.0)).rgb
                   + texture(Sampler0, texCoord0 + vec2(0.0, px.y)).rgb + texture(Sampler0, texCoord0 - vec2(0.0, px.y)).rgb) * 0.25;
        color = clamp(color + (color - blur) * Sharpness * 1.5, 0.0, 1.0);
    }
    fragColor = vec4(color, 1.0) * ColorModulator;
}
