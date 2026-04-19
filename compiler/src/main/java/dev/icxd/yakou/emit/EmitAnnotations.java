package dev.icxd.yakou.emit;

import dev.icxd.yakou.ast.Attribute;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.typeck.ElabContext;
import dev.icxd.yakou.typeck.JavaInterop;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.List;

/**
 * Lowers {@code #{ expr }} metadata to JVM runtime-visible annotations. The
 * expression must name a Java {@code @interface} type (qualified path), or a
 * call {@code Type()} / {@code Type(args)} with the same callee; annotation
 * member values are not emitted yet (marker annotations only).
 */
final class EmitAnnotations {

    private EmitAnnotations() {
    }

    static void emitForClass(ClassVisitor cv, List<Attribute> attrs, ElabContext ctx, JavaInterop java) {
        for (Attribute a : attrs) {
            emitOneOnClass(cv, a, ctx, java);
        }
    }

    static void emitForMethod(MethodVisitor mv, List<Attribute> attrs, ElabContext ctx, JavaInterop java) {
        for (Attribute a : attrs) {
            emitOneOnMethod(mv, a, ctx, java);
        }
    }

    private static void emitOneOnClass(ClassVisitor cv, Attribute a, ElabContext ctx, JavaInterop java) {
        String desc = annotationDescriptor(a.invocation(), ctx, java);
        AnnotationVisitor av = cv.visitAnnotation(desc, true);
        av.visitEnd();
    }

    private static void emitOneOnMethod(MethodVisitor mv, Attribute a, ElabContext ctx, JavaInterop java) {
        String desc = annotationDescriptor(a.invocation(), ctx, java);
        AnnotationVisitor av = mv.visitAnnotation(desc, true);
        av.visitEnd();
    }

    static String annotationDescriptor(Expr invocation, ElabContext ctx, JavaInterop java) {
        Expr target = unwrapAnnotationExpr(invocation);
        if (!(target instanceof Expr.Path p)) {
            throw new CodegenException(
                    "annotation #[{…}] must resolve to a Java annotation type (path or Type(...)), got "
                            + invocation);
        }
        String ykPath = CodegenPaths.qualifyExprPath(p, ctx);
        String internal = JavaInterop.ykPathToInternal(ykPath);
        Class<?> cls = java.loadClassFromInternal(internal)
                .orElseThrow(
                        () -> new CodegenException(
                                "annotation type not found on classpath: " + ykPath));
        if (!cls.isAnnotation()) {
            throw new CodegenException(
                    "#[{…}] target is not a Java annotation type: " + cls.getName());
        }
        return Type.getDescriptor(cls);
    }

    /**
     * Marker annotations only: {@code Foo} or {@code Foo()} or {@code a::Foo()}.
     * Non-empty argument lists are rejected until annotation member emission
     * exists.
     */
    private static Expr unwrapAnnotationExpr(Expr e) {
        return switch (e) {
            case Expr.Call c -> {
                if (!c.args().isEmpty()) {
                    throw new CodegenException(
                            "annotation arguments are not supported yet (use #{ MyAnn } or #{ MyAnn() })");
                }
                yield unwrapAnnotationExpr(c.callee());
            }
            default -> e;
        };
    }
}
