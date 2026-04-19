package dev.icxd.yakou.emit;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Emits a small launcher class {@code yk.Main} whose {@code main(String[])}
 * forwards
 * to {@code yk_main} on the single module class selected at link time.
 */
public final class YkLinker {

    /** Internal name {@code yk/Main} for the launcher class. */
    public static final String LAUNCHER_INTERNAL = "yk/Main";

    private YkLinker() {
    }

    public static void emitLauncher(Path outputDir, String moduleInternalName) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, LAUNCHER_INTERNAL, null, "java/lang/Object",
                null);
        cw.visitSource("Main.yk", null);

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, moduleInternalName, "yk_main", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        Path out = outputDir.resolve(LAUNCHER_INTERNAL + ".class");
        Files.createDirectories(out.getParent());
        Files.write(out, cw.toByteArray());
    }
}
