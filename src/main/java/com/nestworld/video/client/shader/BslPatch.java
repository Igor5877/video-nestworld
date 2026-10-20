package com.nestworld.video.client.shader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Writes a patched copy of the BSL shader pack (the exact version listed in {@link #FILES}) in which the video
 * screens are a separate self-emissive material:
 * <ul>
 *   <li>the picture is the final colour: it goes through the inverse of BSL's own tonemap, so after the shared
 *       post-processing (tonemap, bloom, vignette, grading) it comes out with the colours and the exposure of the
 *       video, in sun and in the dark; sun, shadows and block light are not applied to it;</li>
 *   <li>the casing of the block keeps the pack's normal lighting (it is an ordinary terrain block);</li>
 *   <li>the screen's contribution to bloom is a separate shader option (the picture itself is untouched).</li>
 * </ul>
 * The original pack is never modified. Any file that differs from the expected version stops the patch.
 */
public final class BslPatch {
    /** Block id (category 250-299 "others") given to the screen block in block.properties. */
    public static final int BLOCK_ID = 260;
    public static final String SUFFIX = "_NestworldVideo";
    public static final String MARKER = "shaders/nestworld_display.txt";
    private static final String PATCH_VERSION = "1";

    /** SHA-256 of each file the patch edits, in BSL v10.1.1. */
    private static final Map<String, String> FILES = new LinkedHashMap<>();
    static {
        FILES.put("shaders/block.properties", "530123d96d3dea7dc7a2db97a6b5a1da96fad0da5c1f9554212c0b18820e2644");
        FILES.put("shaders/shaders.properties", "e36a82f25056f449610355104e960ce084a3b0713b1a319d6f799ffe513e2fb7");
        FILES.put("shaders/lib/settings.glsl", "6a40c3c05fde17e9225e83c970ef4e334774b4ffda44ae1396dd0b980fe9a947");
        FILES.put("shaders/program/gbuffers_block.glsl", "50ca6db3a79b1753415011e8b07f7f3617f22239cd754a7f43b7966bbbd67482");
        FILES.put("shaders/program/composite4.glsl", "1b68abbddc3c0e8b65da5979a0d96a7ef889b07cb1ac8047974ef5af08df84af");
        FILES.put("shaders/lang/en_US.lang", "bf10abc0143cd81b6b5ef60a6cff7fc0b87bd13b887b1660923d92fc64e07c25");
    }

    private BslPatch() {}

    public static final class PatchException extends Exception {
        public PatchException(String message) { super(message); }
    }

    /** Where the patched copy of {@code source} is written. */
    public static Path target(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        return source.resolveSibling(stem + SUFFIX + ".zip");
    }

    /** Reads {@code source}, checks it is the supported version and writes the patched copy next to it. */
    public static Path apply(Path source) throws IOException, PatchException {
        if (!Files.isRegularFile(source) || !source.getFileName().toString().endsWith(".zip"))
            throw new PatchException("Очікується shader-pack у вигляді .zip: " + source);
        if (source.getFileName().toString().contains(SUFFIX)) throw new PatchException("Це вже запатчений пак");
        Map<String, byte[]> originals = new LinkedHashMap<>();
        Map<String, byte[]> others = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(source); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                byte[] data = zip.readAllBytes();
                if (FILES.containsKey(e.getName())) originals.put(e.getName(), data);
                else others.put(e.getName(), data);
            }
        }
        for (var file : FILES.entrySet()) {
            byte[] data = originals.get(file.getKey());
            if (data == null) throw new PatchException("У паку немає " + file.getKey() + " - це не BSL v10.1.1 (або інша структура архіву)");
            String hash = sha256(data);
            if (!hash.equals(file.getValue()))
                throw new PatchException("Файл " + file.getKey() + " відрізняється від BSL v10.1.1 (sha256 " + hash.substring(0, 12) + "…). Патч зроблено лише для цієї версії");
        }
        Map<String, byte[]> patched = patch(originals);

        Path out = target(source);
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(os)) {
            for (var e : others.entrySet()) put(zip, e.getKey(), e.getValue());
            for (var e : patched.entrySet()) put(zip, e.getKey(), e.getValue());
            put(zip, MARKER, ("Nestworld Video display patch " + PATCH_VERSION + " for BSL v10.1.1\r\nblock id " + BLOCK_ID + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** The edited contents of the files, all-or-nothing: every anchor has to be found exactly once. */
    static Map<String, byte[]> patch(Map<String, byte[]> originals) throws PatchException {
        Map<String, byte[]> out = new LinkedHashMap<>();

        edit(out, originals, "shaders/block.properties", (t, nl) -> t + nl + "# Nestworld Video: the video screen is its own material" + nl
                + "block." + BLOCK_ID + "00=nestworldvideo:video_screen" + nl);

        edit(out, originals, "shaders/shaders.properties", (t, nl) -> {
            t = replaceOnce(t, "BLOOM_RADIUS BLOOM_STRENGTH BLOOM_CONTRAST DIRTY_LENS", "BLOOM_RADIUS BLOOM_STRENGTH BLOOM_CONTRAST DISPLAY_BRIGHTNESS DISPLAY_BLOOM DIRTY_LENS");
            return replaceOnce(t, "BLOOM_RADIUS BLOOM_STRENGTH BLOOM_CONTRAST LENS_FLARE_STRENGTH", "BLOOM_RADIUS BLOOM_STRENGTH BLOOM_CONTRAST DISPLAY_BRIGHTNESS DISPLAY_BLOOM LENS_FLARE_STRENGTH");
        });

        edit(out, originals, "shaders/lib/settings.glsl", (t, nl) -> replaceOnce(t,
                "  #define BLOOM_CONTRAST 0 //[-4 -3 -2 -1 0 1 2 3 4]" + nl,
                "  #define BLOOM_CONTRAST 0 //[-4 -3 -2 -1 0 1 2 3 4]" + nl
                + "  #define DISPLAY_BRIGHTNESS 100 //[50 60 70 80 90 100 110 120 130 140 150]" + nl
                + "  #define DISPLAY_BLOOM 100 //[0 10 20 30 40 50 60 70 80 90 100 120 150]" + nl
                + "//Nestworld Video: the screen writes a mask into colortex8 (free unless coloured blocklight is on) so its bloom can be scaled//" + nl
                + "  #if DISPLAY_BLOOM != 100 && !defined MULTICOLORED_BLOCKLIGHT && !defined MCBL_SS" + nl
                + "  #define NW_DISPLAY_MASK" + nl
                + "  #endif" + nl));

        edit(out, originals, "shaders/lang/en_US.lang", (t, nl) -> t + nl
                + "option.DISPLAY_BRIGHTNESS=Video Screen Brightness (%)" + nl
                + "option.DISPLAY_BRIGHTNESS.comment=Brightness of the picture on Nestworld Video screens, in percent of the video's own (100 = exactly as encoded). Needs the _NestworldVideo patch." + nl
                + "option.DISPLAY_BLOOM=Video Screen Bloom (%)" + nl
                + "option.DISPLAY_BLOOM.comment=How much the video screens feed the bloom (glow around bright areas). 100 = like any other bright surface, 0 = no halo from the screens." + nl);

        edit(out, originals, "shaders/program/composite4.glsl", (t, nl) -> {
            t = replaceOnce(t, "const bool colortex0MipmapEnabled = true;" + nl,
                    "const bool colortex0MipmapEnabled = true;" + nl
                    + "#ifdef NW_DISPLAY_MASK" + nl
                    + "const bool colortex8MipmapEnabled = true;" + nl
                    + "#endif" + nl);
            t = replaceOnce(t, "uniform sampler2D colortex0;" + nl,
                    "uniform sampler2D colortex0;" + nl
                    + "#ifdef NW_DISPLAY_MASK" + nl
                    + "uniform sampler2D colortex8;" + nl
                    + "#endif" + nl);
            return replaceOnce(t, "\t\t\t\tbloom += texture2D(colortex0, sampleCoord).rgb * wg;" + nl,
                    "\t\t\t\t#ifdef NW_DISPLAY_MASK" + nl
                    + "\t\t\t\tvec3 nwSample = texture2D(colortex0, sampleCoord).rgb;" + nl
                    + "\t\t\t\tnwSample *= mix(1.0, DISPLAY_BLOOM * 0.01, texture2D(colortex8, sampleCoord).r);" + nl
                    + "\t\t\t\tbloom += nwSample * wg;" + nl
                    + "\t\t\t\t#else" + nl
                    + "\t\t\t\tbloom += texture2D(colortex0, sampleCoord).rgb * wg;" + nl
                    + "\t\t\t\t#endif" + nl);
        });

        edit(out, originals, "shaders/program/gbuffers_block.glsl", (t, nl) -> {
            String fragmentMain = "//Program//" + nl + "void main() {" + nl + "    vec4 albedo = texture2D(texture, texCoord) * color;";   // the vertex shader has a main() too
            t = replaceOnce(t, fragmentMain, DISPLAY_FUNCTIONS.replace("\n", nl) + nl + fragmentMain);
            t = replaceOnce(t, "\tif (endPortal < 0.5) {" + nl,
                    "\tfloat displayMask = 0.0;" + nl
                    + "\tif (blockID == " + BLOCK_ID + ") {" + nl
                    + "\t\t// Nestworld Video screen: self-emissive, no lighting, the colour already accounts for the shared tonemap." + nl
                    + "\t\tdisplayMask = 1.0;" + nl
                    + "\t\talbedo = vec4(NwDisplayColor(texture2D(texture, texCoord).rgb), 1.0);" + nl
                    + "\t\t#if ALPHA_BLEND == 0" + nl
                    + "\t\talbedo.rgb = sqrt(albedo.rgb);" + nl
                    + "\t\t#endif" + nl
                    + "\t} else if (endPortal < 0.5) {" + nl);
            return replaceOnce(t,
                    "\t#else" + nl
                    + "\t\t#if defined ADVANCED_MATERIALS && defined REFLECTION_SPECULAR" + nl
                    + "\t\t/* DRAWBUFFERS:0367 */" + nl
                    + "\t\tgl_FragData[1] = vec4(smoothness, skyOcclusion, 0.0, 1.0);" + nl
                    + "\t\tgl_FragData[2] = vec4(EncodeNormal(newNormal), float(gl_FragCoord.z < 1.0), 1.0);" + nl
                    + "\t\tgl_FragData[3] = vec4(fresnel3, 1.0);" + nl
                    + "\t\t#endif" + nl
                    + "\t#endif",
                    "\t#else" + nl
                    + "\t\t#ifdef NW_DISPLAY_MASK" + nl
                    + "\t\t/* DRAWBUFFERS:08 */" + nl
                    + "\t\tgl_FragData[1] = vec4(displayMask, 0.0, 0.0, 1.0);" + nl
                    + "\t\t#endif" + nl
                    + "\t\t#if defined ADVANCED_MATERIALS && defined REFLECTION_SPECULAR" + nl
                    + "\t\t#ifdef NW_DISPLAY_MASK" + nl
                    + "\t\t/* DRAWBUFFERS:03678 */" + nl
                    + "\t\t#else" + nl
                    + "\t\t/* DRAWBUFFERS:0367 */" + nl
                    + "\t\t#endif" + nl
                    + "\t\tgl_FragData[1] = vec4(smoothness, skyOcclusion, 0.0, 1.0);" + nl
                    + "\t\tgl_FragData[2] = vec4(EncodeNormal(newNormal), float(gl_FragCoord.z < 1.0), 1.0);" + nl
                    + "\t\tgl_FragData[3] = vec4(fresnel3, 1.0);" + nl
                    + "\t\t#ifdef NW_DISPLAY_MASK" + nl
                    + "\t\tgl_FragData[4] = vec4(displayMask, 0.0, 0.0, 1.0);" + nl
                    + "\t\t#endif" + nl
                    + "\t\t#endif" + nl
                    + "\t#endif");
        });
        return out;
    }

    /**
     * The picture is the final colour, so it is mapped through the inverse of BSLTonemap (composite5.glsl): scene
     * exposure, desaturating matrix, white curve, lower/upper curve, saturating matrix. The video's code values are
     * decoded with gamma 2.2 because the pack encodes with 1/2.2 after the tonemap. Colours that would need negative
     * light (very saturated primaries) are clipped: the tonemap itself cannot reach them.
     */
    private static final String DISPLAY_FUNCTIONS = """
            //Nestworld Video: self-emissive display///////////////////////////////////////////////////////////////////
            mat3 NwInverseMatrix(mat3 m) {
            \tfloat a00 = m[0][0], a01 = m[0][1], a02 = m[0][2];
            \tfloat a10 = m[1][0], a11 = m[1][1], a12 = m[1][2];
            \tfloat a20 = m[2][0], a21 = m[2][1], a22 = m[2][2];
            \tfloat b01 = a22 * a11 - a12 * a21;
            \tfloat b11 = -a22 * a10 + a12 * a20;
            \tfloat b21 = a21 * a10 - a11 * a20;
            \tfloat det = a00 * b01 + a01 * b11 + a02 * b21;
            \treturn mat3(
            \t\tb01, (-a22 * a01 + a02 * a21), (a12 * a01 - a02 * a11),
            \t\tb11, (a22 * a00 - a02 * a20), (-a12 * a00 + a02 * a10),
            \t\tb21, (-a21 * a00 + a01 * a20), (a11 * a00 - a01 * a10)
            \t) / det;
            }

            vec3 NwDisplayColor(vec3 video) {
            \tvec3 target = pow(clamp(video, vec3(0.0), vec3(0.996)), vec3(2.2)) * (DISPLAY_BRIGHTNESS * 0.01);
            \tfloat s = TONEMAP_WHITE_PATH;
            \tfloat a = 0.03 * s;
            \tfloat b = 0.01 * s;
            \tfloat c = 1.0 - (a + b);
            \tfloat d = 1.0 - (a + a);
            \tmat3 desatMatrix = mat3(vec3(c, a, b), vec3(a, d, a), vec3(b, a, c));
            \tmat3 satMatrix = NwInverseMatrix(desatMatrix);
            \tvec3 u = clamp(target * desatMatrix, vec3(0.0), vec3(0.9995));
            \tvec3 w = u;
            \tfor (int i = 0; i < 4; i++) w = pow(u, 1.0 / mix(vec3(TONEMAP_LOWER_CURVE), vec3(TONEMAP_UPPER_CURVE), sqrt(w)));
            \tvec3 y = w / pow(1.0 - pow(w, vec3(TONEMAP_WHITE_CURVE)), vec3(1.0 / TONEMAP_WHITE_CURVE));
            \tfloat selfBloom = 0.0;
            #if defined NW_DISPLAY_MASK && BLOOM_CONTRAST == 0 && !defined DISABLE_BLOOM
            \t// Bloom mixes the screen's own pixels with a blur that now holds only DISPLAY_BLOOM of the screen: undo that for the big, uniform parts.
            \tselfBloom = clamp(0.2 * BLOOM_STRENGTH * (1.0 - DISPLAY_BLOOM * 0.01), -0.33, 0.33);
            #endif
            \treturn max(y * satMatrix, vec3(0.0)) / exp2(2.0 + EXPOSURE) / (1.0 - selfBloom);
            }
            """;

    private interface Edit { String apply(String text, String nl) throws PatchException; }

    private static void edit(Map<String, byte[]> out, Map<String, byte[]> originals, String name, Edit edit) throws PatchException {
        String text = new String(originals.get(name), StandardCharsets.UTF_8);
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        out.put(name, edit.apply(text, nl).getBytes(StandardCharsets.UTF_8));
    }

    private static String replaceOnce(String text, String anchor, String replacement) throws PatchException {
        int at = text.indexOf(anchor);
        if (at < 0 || text.indexOf(anchor, at + 1) >= 0)
            throw new PatchException("Не вдалося знайти єдине місце для патча: «" + anchor.strip().split("\\R")[0] + "»");
        return text.substring(0, at) + replacement + text.substring(at + anchor.length());
    }
}
