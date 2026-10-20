package com.nestworld.video.client.shader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Run with an original BSL archive and an empty scratch directory. Never writes to the supplied archive. */
public final class ShaderPatchCheck {
    private static Map<String, byte[]> read(Path p) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (var z = new ZipInputStream(Files.newInputStream(p))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null;) result.put(e.getName(), z.readAllBytes());
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]), dir = Path.of(args[1]);
        Files.createDirectories(dir);
        Path copy = dir.resolve("BSL_v10.1.1.zip");
        Files.copy(source, copy);
        byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(copy));
        Path out = BslPatch.apply(copy);
        if (!Arrays.equals(before, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(copy))))
            throw new AssertionError("Original changed");
        Map<String, byte[]> original = read(copy), patched = read(out);
        int changed = 0;
        for (var e : original.entrySet()) {
            if (!Arrays.equals(e.getValue(), patched.get(e.getKey()))) changed++;
        }
        if (changed != 7 || patched.size() != original.size() + 1) throw new AssertionError("Unexpected files changed");
        if (!new String(patched.get(BslPatch.MARKER), java.nio.charset.StandardCharsets.UTF_8).contains("patch 4"))
            throw new AssertionError("Missing v4 marker");
        try { BslPatch.apply(out); throw new AssertionError("Allowed repeated patch"); }
        catch (BslPatch.PatchException expected) { }

        Path bad = dir.resolve("BSL_modified.zip");
        try (var z = new ZipOutputStream(Files.newOutputStream(bad))) {
            for (var e : original.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                if (e.getKey().equals("shaders/program/gbuffers_block.glsl")) z.write(10);
                z.closeEntry();
            }
        }
        try { BslPatch.apply(bad); throw new AssertionError("Allowed unsupported source"); }
        catch (BslPatch.PatchException expected) { }
        if (Files.exists(BslPatch.target(bad))) throw new AssertionError("Partial output on rejected pack");
        System.out.println("PASS: patch v4, original unchanged, seven edited files, repeated/unsupported packs rejected");
        System.out.println(out);
    }
}
