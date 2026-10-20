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
 *   <li>the picture emits bounded linear light, independent of sun, shadows and block light.
 *       BSL's exposure, tonemap and grading are applied once, like the rest of the scene;</li>
 *   <li>the casing of the block keeps the pack's normal lighting (it is an ordinary terrain block);</li>
 *   <li>the screen's contribution to bloom is a separate shader option (the picture itself is untouched).</li>
 * </ul>
 * The original pack is never modified. Any file that differs from the expected version stops the patch.
 */
public final class BslPatch {
    /**
     * Block id (category 250-299 "others") given to the screen block in block.properties. The screen's 125 light colours
     * ({@code VideoScreenBlock.GLOW}) are the ids {@code BLOCK_ID * 100 + glow}, so block ids {@code BLOCK_ID} and
     * {@code BLOCK_ID + 1} (glow 100..124) both belong to the screen.
     */
    public static final int BLOCK_ID = 260;
    public static final int GLOW_VALUES = 125;
    public static final String SUFFIX = "_NestworldVideo";
    public static final String MARKER = "shaders/nestworld_display.txt";
    private static final String PATCH_VERSION = "3";

    /** SHA-256 of each file the patch edits, in BSL v10.1.1. */
    private static final Map<String, String> FILES = new LinkedHashMap<>();
    static {
        FILES.put("shaders/block.properties", "530123d96d3dea7dc7a2db97a6b5a1da96fad0da5c1f9554212c0b18820e2644");
        FILES.put("shaders/shaders.properties", "e36a82f25056f449610355104e960ce084a3b0713b1a319d6f799ffe513e2fb7");
        FILES.put("shaders/lib/settings.glsl", "6a40c3c05fde17e9225e83c970ef4e334774b4ffda44ae1396dd0b980fe9a947");
        FILES.put("shaders/program/gbuffers_block.glsl", "50ca6db3a79b1753415011e8b07f7f3617f22239cd754a7f43b7966bbbd67482");
        FILES.put("shaders/program/shadow.glsl", "c0ffcfe2885f9ea9566f08236fd2d3a2d6163d7c049f8ceae2f595b5b82c9376");
        FILES.put("shaders/lib/lighting/voxelColorFetch.glsl", "fe9f5015428acc2e258f68b512fb4cb445df6e872be5d26bfea86e0094ecd558");
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

        edit(out, originals, "shaders/block.properties", (t, nl) -> {
            StringBuilder b = new StringBuilder(t).append(nl).append("# Nestworld Video: the video screen is its own material; each light colour (glow) is its own id").append(nl);
            for (int glow = 0; glow < GLOW_VALUES; glow++)
                b.append("block.").append(BLOCK_ID * 100 + glow).append("=nestworldvideo:video_screen:glow=").append(glow).append(nl);
            return b.toString();
        });

        edit(out, originals, "shaders/shaders.properties", (t, nl) -> {
            t = replaceOnce(t, "BLOOM_RADIUS BLOOM_STRENGTH BLOOM_CONTRAST DIRTY_LENS", "BLOOM_RADIUS BLOOM_STRENGTH BLOOM_CONTRAST DISPLAY_BRIGHTNESS DISPLAY_BLOOM DISPLAY_LIGHT DIRTY_LENS");
            return replaceOnce(t, "BLOOM_RADIUS BLOOM_STRENGTH BLOOM_CONTRAST LENS_FLARE_STRENGTH", "BLOOM_RADIUS BLOOM_STRENGTH BLOOM_CONTRAST DISPLAY_BRIGHTNESS DISPLAY_BLOOM DISPLAY_LIGHT LENS_FLARE_STRENGTH");
        });

        edit(out, originals, "shaders/lib/settings.glsl", (t, nl) -> replaceOnce(replaceOnce(t,
                "//#define MULTICOLORED_BLOCKLIGHT" + nl,
                "  #define MULTICOLORED_BLOCKLIGHT" + nl),
                "  #define BLOOM_CONTRAST 0 //[-4 -3 -2 -1 0 1 2 3 4]" + nl,
                "  #define BLOOM_CONTRAST 0 //[-4 -3 -2 -1 0 1 2 3 4]" + nl
                + "  #define DISPLAY_BRIGHTNESS 100 //[25 50 60 70 80 90 100 110 120 130 140 150 200]" + nl
                + "  #define DISPLAY_BLOOM 20 //[0 10 20 30 40 50 60 70 80 90 100 120 150]" + nl
                + "  #define DISPLAY_LIGHT 60 //[0 20 30 40 50 60 80 100 125 150 200]" + nl
                + "//Nestworld Video: the screen writes a mask into colortex8 (free unless screen-space coloured light is on) so its bloom can be scaled//" + nl
                + "  #if DISPLAY_BLOOM != 100 && !defined MCBL_SS" + nl
                + "  #define NW_DISPLAY_MASK" + nl
                + "  #endif" + nl));

        edit(out, originals, "shaders/lang/en_US.lang", (t, nl) -> t + nl
                + "option.DISPLAY_BRIGHTNESS=Video Screen Brightness (%)" + nl
                + "option.DISPLAY_BRIGHTNESS.comment=Emitted light of Nestworld Video screens (100 = normal display brightness). BSL exposure and grading still apply." + nl
                + "option.DISPLAY_BLOOM=Video Screen Bloom (%)" + nl
                + "option.DISPLAY_BLOOM.comment=How much the video screens feed bloom. 20 = subtle, 100 = full, 0 = none. Requires MCBL_SS off." + nl
                + "option.DISPLAY_LIGHT=Video Screen Light (%)" + nl
                + "option.DISPLAY_LIGHT.comment=Brightness of the coloured light the video screens give the room (needs Multicolored Blocklight). 0 = none." + nl);

        // The voxel map holds 8 bits per block: BSL uses 1..99, the screen's light colours are 101..224 (100 + glow).
        edit(out, originals, "shaders/program/shadow.glsl", (t, nl) -> replaceOnce(t,
                "\tUpdateVoxelMap(gl_VertexID, blockData);" + nl,
                "\t// Nestworld Video: the screen's light colour (block ids " + BLOCK_ID + "00..) goes into the voxel map as 100 + glow" + nl
                + "\tint nwVoxelData = blockData;" + nl
                + "\tif (blockID == " + BLOCK_ID + " || blockID == " + (BLOCK_ID + 1) + ") nwVoxelData = (blockData > 0 || blockID == " + (BLOCK_ID + 1) + ") ? 100 + (blockID - " + BLOCK_ID + ") * 100 + blockData : 0;" + nl
                + "\tUpdateVoxelMap(gl_VertexID, nwVoxelData);" + nl));

        edit(out, originals, "shaders/lib/lighting/voxelColorFetch.glsl", (t, nl) -> replaceOnce(t,
                "\treturn emission;",
                "\t// Nestworld Video: 5 x 5 x 5 levels of red, green and blue (100 + r * 25 + g * 5 + b), the colour of a lit screen tile" + nl
                + "\tif (data >= 101 && data <= 224) {" + nl
                + "\t\tuint nwIndex = data - 100;" + nl
                + "\t\tfloat nwLevels[5] = float[5](0.0, 0.05, 0.15, 0.4, 1.0);" + nl
                + "\t\temission = vec3(nwLevels[nwIndex / 25], nwLevels[(nwIndex / 5) % 5], nwLevels[nwIndex % 5]) * (4.0 * DISPLAY_LIGHT * 0.01);" + nl
                + "\t}" + nl
                + "\treturn emission;"));

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
            // Dynamic video is not in the block atlas and has no parallax/normal maps.
            t = replaceOnce(t, "float skipParallax = float(blockID == 251);",
                    "float skipParallax = float(blockID == 251 || NW_IS_DISPLAY(blockID));");
            t = replaceOnce(t, "\tif (endPortal < 0.5) {" + nl,
                    "\tfloat displayMask = 0.0;" + nl
                    + "\tif (NW_IS_DISPLAY(blockID)) {" + nl
                    + "\t\t// Nestworld Video screen: self-emissive linear radiance; shared exposure and tonemap apply once." + nl
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
     * Decode the same gamma convention BSL uses for texture colour, then set a finite
     * display white level. Never invert the tonemap: that turns near-white SDR pixels
     * into extreme HDR sources before bloom, and clips negative values for primaries.
     * 0.35 scene-linear white gives a readable display at BSL's default exposure (+2 stops).
     */
    private static final String DISPLAY_FUNCTIONS = """
            //Nestworld Video: self-emissive display///////////////////////////////////////////////////////////////////
            #define NW_IS_DISPLAY(id) ((id) == 260 || (id) == 261)
            vec3 NwDisplayColor(vec3 video) {
                vec3 linearVideo = pow(clamp(video, vec3(0.0), vec3(1.0)), vec3(2.2));
                return linearVideo * (0.35 * DISPLAY_BRIGHTNESS * 0.01);
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
