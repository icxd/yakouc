package dev.icxd.yakou.emit;

import dev.icxd.yakou.typeck.JavaInterop;
import dev.icxd.yakou.typeck.Ty;
import dev.icxd.yakou.typeck.TypeTable;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JVM calls into imported Java classes via reflection (mirror compile
 * classpath).
 */
final class JavaEmit {

    private JavaEmit() {
    }

    /**
     * Emits GETSTATIC chain for paths like {@code System::out}
     * ({@code use java::lang::System}).
     */
    static void emitStaticFieldChain(MethodVisitor mv, JavaInterop java, TypeTable table, List<String> segments) {
        if (segments.size() < 2) {
            throw new CodegenException("java path needs at least two segments, got " + segments);
        }
        String internal0 = table.javaInternalName(segments.getFirst()).orElseThrow(
                () -> new CodegenException("not a java import path: " + segments.getFirst()));
        Class<?> cur = loadInternal(java, internal0);
        for (int i = 1; i < segments.size(); i++) {
            String seg = segments.get(i);
            boolean last = i == segments.size() - 1;
            try {
                var f = cur.getField(seg);
                if (!Modifier.isStatic(f.getModifiers())) {
                    throw new CodegenException("expected static field in chain: " + segments);
                }
                String owner = Type.getInternalName(f.getDeclaringClass());
                String desc = Type.getDescriptor(f.getType());
                mv.visitFieldInsn(Opcodes.GETSTATIC, owner, seg, desc);
                if (last) {
                    return;
                }
                if (f.getType().isPrimitive() || f.getType().isArray()) {
                    throw new CodegenException("cannot chain through primitive/array field: " + seg);
                }
                cur = f.getType();
            } catch (NoSuchFieldException e) {
                throw new CodegenException("no field '" + seg + "' on " + cur.getName());
            }
        }
    }

    /**
     * {@link Opcodes#GETFIELD} for a public instance field. Receiver must already
     * be
     * on the stack.
     */
    static void emitInstanceFieldGet(
            MethodVisitor mv, JavaInterop java, String javaYkPath, String fieldName) {
        Class<?> clazz = loadYkNominal(java, javaYkPath);
        Field f;
        try {
            f = clazz.getField(fieldName);
        } catch (NoSuchFieldException e) {
            throw new CodegenException("no field '" + fieldName + "' on " + clazz.getName());
        }
        if (Modifier.isStatic(f.getModifiers())) {
            throw new CodegenException("expected instance field, got static: " + fieldName);
        }
        String owner = Type.getInternalName(f.getDeclaringClass());
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, fieldName, Type.getDescriptor(f.getType()));
    }

    static void emitVirtualCall(
            MethodVisitor mv,
            JavaInterop java,
            TypeTable table,
            Ty receiverTy,
            String methodName,
            List<Ty> argTys,
            Runnable emitReceiver,
            Runnable emitArgs) {
        Ty d = Ty.deref(receiverTy);
        if (!(d instanceof Ty.NomTy nom)) {
            throw new CodegenException("virtual call requires nominal receiver, got " + receiverTy);
        }
        Class<?> clazz = lookupVirtualReceiverClass(java, table, nom.path());
        Class<?>[] paramClasses = new Class[argTys.size()];
        for (int i = 0; i < argTys.size(); i++) {
            paramClasses[i] = tyToReflectClass(java, table, argTys.get(i));
        }
        Method m = resolveVirtualMethod(clazz, methodName, paramClasses);
        emitReceiver.run();
        emitArgs.run();
        Class<?> declaring = m.getDeclaringClass();
        String owner = Type.getInternalName(declaring);
        String md = Type.getMethodDescriptor(m);
        if (declaring.isInterface()) {
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, owner, m.getName(), md, true);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, m.getName(), md, false);
        }
    }

    static void emitStaticCall(
            MethodVisitor mv,
            JavaInterop java,
            TypeTable table,
            String javaYkPath,
            String methodName,
            List<Ty> argTys,
            Runnable emitArgs) {
        Class<?> clazz = loadYkNominal(java, javaYkPath);
        Class<?>[] paramClasses = new Class[argTys.size()];
        for (int i = 0; i < argTys.size(); i++) {
            paramClasses[i] = tyToReflectClass(java, table, argTys.get(i));
        }
        java.lang.reflect.Method m;
        try {
            m = clazz.getMethod(methodName, paramClasses);
        } catch (NoSuchMethodException e) {
            throw new CodegenException("no static method " + methodName + " on " + clazz.getName());
        }
        if (!Modifier.isStatic(m.getModifiers())) {
            throw new CodegenException("expected static method " + methodName + " on " + clazz.getName());
        }
        emitArgs.run();
        String owner = Type.getInternalName(m.getDeclaringClass());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, m.getName(), Type.getMethodDescriptor(m), false);
    }

    private static Method resolveVirtualMethod(Class<?> clazz, String name, Class<?>[] params) {
        try {
            return clazz.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {
        }
        List<Method> candidates = new ArrayList<>();
        for (Method me : clazz.getMethods()) {
            if (!me.getName().equals(name)) {
                continue;
            }
            if (Modifier.isStatic(me.getModifiers())) {
                continue;
            }
            if (matchesParamsAssignable(me.getParameterTypes(), params)) {
                candidates.add(me);
            }
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        if (candidates.isEmpty()) {
            throw new CodegenException("no virtual method " + name + " on " + clazz.getName());
        }
        throw new CodegenException("ambiguous overload for " + name + " on " + clazz.getName());
    }

    /** Reflect arg types derived from Yakou types (actual argument classes). */
    private static boolean matchesParamsAssignable(Class<?>[] declared, Class<?>[] wanted) {
        if (declared.length != wanted.length) {
            return false;
        }
        for (int i = 0; i < declared.length; i++) {
            if (!declared[i].isAssignableFrom(wanted[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> tyToReflectClass(JavaInterop java, TypeTable table, Ty t) {
        Ty d = Ty.deref(t);
        return switch (d) {
            case Ty.PrimTy p -> primReflectClass(p.prim());
            case Ty.ArrayTy ar -> reflectArrayClass(java, table, ar.element());
            case Ty.NomTy n -> lookupNominalReflectClass(java, table, n.path());
            default -> throw new CodegenException("unsupported java call arg type: " + t);
        };
    }

    /**
     * Class token for overload resolution when calling Java from Yakou.
     * Yakou-defined
     * nominals are not on the compile classpath yet; use a loadable JVM supertype
     * or declared interface instead (mirrors {@link #lookupVirtualReceiverClass}).
     */
    static Class<?> lookupNominalReflectClass(JavaInterop java, TypeTable table, String ykPath) {
        Optional<Class<?>> direct = java.loadClassFromInternal(JavaInterop.ykPathToInternal(ykPath));
        if (direct.isPresent()) {
            return direct.get();
        }
        Optional<String> supPath = table.immediateSuperClass(ykPath);
        while (supPath.isPresent()) {
            String p = supPath.get();
            if (java.hasLoadableClass(p)) {
                return java.loadClassFromInternal(JavaInterop.ykPathToInternal(p))
                        .orElseThrow(
                                () -> new CodegenException(
                                        "classpath type missing for argument type dispatch: " + p));
            }
            supPath = table.immediateSuperClass(p);
        }
        for (String iface : table.traitInterfacesJvmOrder(ykPath)) {
            if (java.hasLoadableClass(iface)) {
                return java.loadClassFromInternal(JavaInterop.ykPathToInternal(iface))
                        .orElseThrow(
                                () -> new CodegenException(
                                        "classpath type missing for interface " + iface));
            }
        }
        String dot = JavaInterop.ykPathToInternal(ykPath).replace('/', '.');
        throw new CodegenException("class not found on classpath: " + dot);
    }

    private static Class<?> primReflectClass(dev.icxd.yakou.typeck.Prim p) {
        return switch (p) {
            case BOOL -> boolean.class;
            case I8 -> byte.class;
            case I16 -> short.class;
            case I32, U16, U32 -> int.class;
            case I64 -> long.class;
            case F32 -> float.class;
            case F64 -> double.class;
            case STR -> String.class;
            case UNIT -> void.class;
            case BYTES -> byte[].class;
        };
    }

    static Class<?> loadInternal(JavaInterop java, String slashInternalName) {
        String dot = slashInternalName.replace('/', '.');
        try {
            return Class.forName(dot, false, java.loader());
        } catch (ClassNotFoundException e) {
            throw new CodegenException("class not found on classpath: " + dot);
        }
    }

    static Class<?> loadYkNominal(JavaInterop java, String ykPath) {
        String dot = JavaInterop.ykPathToInternal(ykPath).replace('/', '.');
        try {
            return Class.forName(dot, false, java.loader());
        } catch (ClassNotFoundException e) {
            throw new CodegenException("class not found on classpath: " + dot);
        }
    }

    private static Class<?> reflectArrayClass(JavaInterop java, TypeTable table, Ty element) {
        Class<?> inner = tyToReflectClass(java, table, element);
        return Array.newInstance(inner, 0).getClass();
    }

    /**
     * Class to use for reflection when resolving virtual methods; if the Yakou
     * class is not on the classpath yet, use the nearest loadable JVM superclass
     * ({@link TypeTable#immediateSuperClass}).
     */
    static Class<?> lookupVirtualReceiverClass(JavaInterop java, TypeTable table, String receiverYkPath) {
        Optional<Class<?>> direct = java.loadClassFromInternal(JavaInterop.ykPathToInternal(receiverYkPath));
        if (direct.isPresent()) {
            return direct.get();
        }
        Optional<String> supPath = table.immediateSuperClass(receiverYkPath);
        while (supPath.isPresent()) {
            if (java.hasLoadableClass(supPath.get())) {
                String expected = supPath.get();
                return java.loadClassFromInternal(JavaInterop.ykPathToInternal(expected))
                        .orElseThrow(
                                () -> new CodegenException(
                                        "classpath type missing for inherited dispatch: "
                                                + expected));
            }
            supPath = table.immediateSuperClass(supPath.get());
        }
        throw new CodegenException(
                "cannot resolve JVM receiver class for virtual method on " + receiverYkPath);
    }

}
