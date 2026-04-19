package dev.icxd.yakou.emit;

import org.objectweb.asm.ClassWriter;

/**
 * Attaches JVM debug metadata ({@link ClassWriter#visitSource}) to generated
 * classes.
 */
final class EmitDebug {

    private EmitDebug() {
    }

    static void visitSource(ClassWriter cw, String sourceFileName) {
        cw.visitSource(sourceFileName, null);
    }
}
