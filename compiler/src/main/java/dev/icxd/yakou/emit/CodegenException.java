package dev.icxd.yakou.emit;

/** Code generation failed (unsupported construct or invalid program). */
public final class CodegenException extends RuntimeException {

    public CodegenException(String message) {
        super(message);
    }
}
