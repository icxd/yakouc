package dev.icxd.yakou.diagnostics;

/**
 * Minimal ANSI styling. Respects {@code NO_COLOR} and {@code CLICOLOR} /
 * {@code FORCE_COLOR}.
 */
public final class Ansi {

    private Ansi() {
    }

    public static boolean useColor() {
        String noColor = System.getenv("NO_COLOR");
        if (noColor != null && !noColor.isEmpty()) {
            return false;
        }
        if ("0".equals(System.getenv("CLICOLOR"))) {
            return false;
        }
        if ("1".equals(System.getenv("FORCE_COLOR")) || "1".equals(System.getenv("CLICOLOR_FORCE"))) {
            return true;
        }
        return System.console() != null;
    }

    /** Bold bright red, for {@code error}. */
    public static String errorWord() {
        return "\u001B[1;91merror\u001B[0m";
    }

    public static String bold(String s) {
        return "\u001B[1m" + s + "\u001B[0m";
    }

    public static String dim(String s) {
        return "\u001B[2m" + s + "\u001B[0m";
    }

    public static String green(String s) {
        return "\u001B[32m" + s + "\u001B[0m";
    }

    public static String brightCyan(String s) {
        return "\u001B[96m" + s + "\u001B[0m";
    }

    public static String yellow(String s) {
        return "\u001B[33m" + s + "\u001B[0m";
    }
}
