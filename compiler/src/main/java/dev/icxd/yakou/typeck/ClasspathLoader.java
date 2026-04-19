package dev.icxd.yakou.typeck;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ClassLoader that sees the compiler {@code -cp} entries plus the JDK (parent).
 */
final class ClasspathLoader {

    private ClasspathLoader() {
    }

    static ClassLoader forCompileClasspath(List<Path> classpath) {
        ClassLoader parent = ClassLoader.getPlatformClassLoader();
        if (classpath == null || classpath.isEmpty()) {
            return parent;
        }
        URL[] urls = classpath.stream()
                .filter(p -> Files.exists(p))
                .map(
                        p -> {
                            try {
                                return p.toUri().toURL();
                            } catch (MalformedURLException e) {
                                throw new IllegalArgumentException(p.toString(), e);
                            }
                        })
                .toArray(URL[]::new);
        if (urls.length == 0) {
            return parent;
        }
        return URLClassLoader.newInstance(urls, parent);
    }
}
