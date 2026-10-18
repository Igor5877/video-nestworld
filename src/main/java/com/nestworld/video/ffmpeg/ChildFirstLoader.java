package com.nestworld.video.ffmpeg;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Loads the downloaded JavaCV/FFmpeg jars and the worker implementation in isolation from Forge's module
 * class loader, so the libraries need no module descriptor and cannot clash with other mods. Everything
 * else (JDK, slf4j, the mod's own interfaces) is delegated to the parent.
 */
final class ChildFirstLoader extends URLClassLoader {
    private static final String IMPL_PACKAGE = "com.nestworld.video.ffmpeg.impl.";
    static { registerAsParallelCapable(); }

    private final ClassLoader modLoader;

    ChildFirstLoader(URL[] libraries, ClassLoader modLoader) {
        super(libraries, modLoader);
        this.modLoader = modLoader;
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (name.startsWith("org.bytedeco.")) {
                    try { loaded = findClass(name); } catch (ClassNotFoundException ignored) { }
                } else if (name.startsWith(IMPL_PACKAGE)) {
                    loaded = defineFromMod(name);
                }
                if (loaded == null) return super.loadClass(name, resolve);
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    /** The worker classes live in the mod jar; read their bytes and define them here instead. */
    private Class<?> defineFromMod(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/') + ".class";
        try (InputStream in = modLoader.getResourceAsStream(path)) {
            if (in == null) throw new ClassNotFoundException(name);
            byte[] bytes = in.readAllBytes();
            return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }
}
