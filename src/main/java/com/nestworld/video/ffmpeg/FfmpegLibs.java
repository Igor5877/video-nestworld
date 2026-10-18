package com.nestworld.video.ffmpeg;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Provides the FFmpeg decoder without shipping its ~25 MB native libraries inside the mod: the JavaCV jars
 * for the current platform are downloaded once from Maven Central into the game directory, verified against
 * their published SHA-1, and loaded by an isolated class loader.
 */
public final class FfmpegLibs {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String JAVACPP = "1.5.10", JAVACV = "1.5.10", FFMPEG = "6.1.1-1.5.10";
    private static final String[] MIRRORS = {
            "https://repo1.maven.org/maven2/",
            "https://maven-central.storage-download.googleapis.com/maven2/",
            "https://repo.maven.apache.org/maven2/"};
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public enum State { IDLE, DOWNLOADING, READY, FAILED }

    private static final Object LOCK = new Object();
    private static State state = State.IDLE;
    private static String message = "";
    private static ChildFirstLoader loader;
    private static Path baseDir;

    private FfmpegLibs() {}

    public static synchronized State state() { return state; }
    public static synchronized String message() { return message; }

    /** Starts preparing the libraries in the background; cheap to call repeatedly. */
    public static void prepareAsync(Path gameDirectory) {
        synchronized (LOCK) {
            if (state != State.IDLE) return;
            state = State.DOWNLOADING;
            message = "Preparing FFmpeg";
            baseDir = gameDirectory.resolve("nestworld-video");
        }
        Thread thread = new Thread(FfmpegLibs::prepare, "nestworld-ffmpeg-setup");
        thread.setDaemon(true);
        thread.start();
    }

    /** Creates a decoder; only valid once {@link #state()} is READY. */
    public static VideoDecoder newDecoder() {
        ChildFirstLoader current;
        synchronized (LOCK) {
            if (state != State.READY) throw new IllegalStateException("FFmpeg libraries are not ready: " + state);
            current = loader;
        }
        try {
            Class<?> worker = Class.forName("com.nestworld.video.ffmpeg.impl.FfmpegWorker", true, current);
            return (VideoDecoder) worker.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Cannot create the FFmpeg decoder", e);
        }
    }

    private static void prepare() {
        try {
            String platform = platform();
            Path libs = baseDir.resolve("ffmpeg").resolve(FFMPEG);
            Files.createDirectories(libs);
            List<Artifact> artifacts = List.of(
                    new Artifact("javacpp", JAVACPP, ""),
                    new Artifact("javacpp", JAVACPP, platform),
                    new Artifact("javacv", JAVACV, ""),
                    new Artifact("ffmpeg", FFMPEG, ""),
                    new Artifact("ffmpeg", FFMPEG, platform));
            List<URL> urls = new ArrayList<>();
            for (Artifact artifact : artifacts) {
                Path file = libs.resolve(artifact.fileName());
                if (!Files.isRegularFile(file) || !artifact.matches(file)) {
                    setMessage("Downloading " + artifact.fileName());
                    artifact.download(file);
                }
                urls.add(file.toUri().toURL());
            }
            // JavaCPP unpacks its JNI libraries here; keep it inside the (usually ASCII-only) game directory.
            System.setProperty("org.bytedeco.javacpp.cachedir", baseDir.resolve("javacpp-cache").toString());
            ChildFirstLoader created = new ChildFirstLoader(urls.toArray(new URL[0]), FfmpegLibs.class.getClassLoader());
            synchronized (LOCK) { loader = created; state = State.READY; message = "Ready"; }
            LOGGER.info("Nestworld Video: FFmpeg decoder ready ({})", platform);
        } catch (Throwable t) {
            LOGGER.warn("Nestworld Video: FFmpeg decoder is unavailable: {}", t.toString());
            synchronized (LOCK) { state = State.FAILED; message = t.getMessage() == null ? t.toString() : t.getMessage(); }
        }
    }

    private static void setMessage(String text) { synchronized (LOCK) { message = text; } }

    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
        if (os.contains("win") && x64) return "windows-x86_64";
        if (os.contains("mac") && arm) return "macosx-arm64";
        if (os.contains("mac") && x64) return "macosx-x86_64";
        if (os.contains("linux") && x64) return "linux-x86_64";
        if (os.contains("linux") && arm) return "linux-arm64";
        throw new IllegalStateException("Unsupported platform for the FFmpeg decoder: " + os + " / " + arch);
    }

    private record Artifact(String name, String version, String classifier) {
        String fileName() { return name + "-" + version + (classifier.isEmpty() ? "" : "-" + classifier) + ".jar"; }
        String path() { return "org/bytedeco/" + name + "/" + version + "/" + fileName(); }

        boolean matches(Path file) {
            try {
                String expected = fetchText(path() + ".sha1");
                return expected != null && expected.equalsIgnoreCase(sha1(file));
            } catch (Exception e) {
                // Offline with a file we already verified earlier: keep using it.
                return true;
            }
        }

        void download(Path target) throws IOException, InterruptedException {
            IOException last = null;
            for (String mirror : MIRRORS) {
                Path temp = target.resolveSibling(target.getFileName() + ".part");
                try {
                    HttpResponse<InputStream> response = HTTP.send(HttpRequest.newBuilder(URI.create(mirror + path()))
                            .timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " from " + mirror);
                    try (InputStream in = response.body()) { Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING); }
                    String expected = fetchText(path() + ".sha1");
                    if (expected != null && !expected.equalsIgnoreCase(sha1(temp)))
                        throw new IOException("Checksum mismatch for " + fileName());
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (IOException e) {
                    last = e;
                    Files.deleteIfExists(temp);
                    LOGGER.warn("Nestworld Video: download of {} from {} failed: {}", fileName(), mirror, e.toString());
                }
            }
            throw new IOException("Cannot download " + fileName() + ": " + (last == null ? "no mirror" : last.getMessage()), last);
        }
    }

    private static String fetchText(String path) throws IOException, InterruptedException {
        for (String mirror : MIRRORS) {
            try {
                HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(mirror + path))
                        .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return response.body().trim().split("\\s+")[0];
            } catch (IOException ignored) { /* try the next mirror */ }
        }
        return null;
    }

    private static String sha1(Path file) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-1"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
