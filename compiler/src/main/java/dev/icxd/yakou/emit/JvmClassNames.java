package dev.icxd.yakou.emit;

import java.nio.file.Path;

/**
 * Maps a {@code .yk} source path to an internal class name {@code yk/FooYk}.
 */
public final class JvmClassNames {

    private JvmClassNames() {
    }

    public static String internalNameFromSource(Path sourcePath) {
        String fileName = sourcePath.getFileName().toString();
        if (!fileName.endsWith(".yk")) {
            fileName = fileName + ".yk";
        }
        String stem = fileName.substring(0, fileName.length() - 3);
        String safe = sanitizeJavaIdentifier(stem);
        if (safe.isEmpty()) {
            safe = "Module";
        }
        return "yk/" + safe + "Yk";
    }

    /** {@code demo::max} → {@code demo__max} */
    public static String mangleQualifiedFn(String qualified) {
        return qualified.replace("::", "__");
    }

    /**
     * User {@code fn main} is emitted as {@code yk_main} to leave JVM
     * {@code main(String[])} free.
     */
    public static String mangleMethodName(String qualifiedFnName) {
        if ("main".equals(qualifiedFnName)) {
            return "yk_main";
        }
        return mangleQualifiedFn(qualifiedFnName);
    }

    private static String sanitizeJavaIdentifier(String stem) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stem.length(); i++) {
            char c = stem.charAt(i);
            if (Character.isJavaIdentifierPart(c)) {
                sb.append(c);
            } else if (c == '-' || c == '.' || c == ' ') {
                sb.append('_');
            }
        }
        if (sb.isEmpty()) {
            return "M";
        }
        if (!Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }
}
