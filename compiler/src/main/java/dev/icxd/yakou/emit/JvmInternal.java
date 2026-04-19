package dev.icxd.yakou.emit;

/**
 * Yakou qualified paths ({@code demo::User}) ↔ JVM internal names
 * ({@code demo/User}).
 */
public final class JvmInternal {

    private JvmInternal() {
    }

    public static String qualified(String yakouPath) {
        return yakouPath.replace("::", "/");
    }

    /** {@code demo/User} → binary name {@code demo.User}. */
    public static String qualifiedToBinaryClassName(String yakouPath) {
        return yakouPath.replace("::", ".");
    }

    /** {@code demo/User.class} layout under output dir from {@code demo::User}. */
    public static java.nio.file.Path relativeClassFile(String yakouQualifiedPath) {
        return java.nio.file.Path.of(qualified(yakouQualifiedPath) + ".class");
    }
}
