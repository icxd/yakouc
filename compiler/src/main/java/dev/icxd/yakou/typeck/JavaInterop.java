package dev.icxd.yakou.typeck;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves JVM fields and methods using reflection on the compile classpath
 * (no ASM — keeps the {@code yakouc} thin JAR runnable without shading deps).
 */
public final class JavaInterop {

    private final ClassLoader loader;

    public JavaInterop(List<Path> classpath) {
        this.loader = ClasspathLoader.forCompileClasspath(classpath);
    }

    public ClassLoader loader() {
        return loader;
    }

    /** {@code java/lang/Foo → java::lang::Foo} */
    public static String internalNameToYkPath(String internal) {
        return String.join("::", internal.split("/"));
    }

    public static String ykPathToInternal(String ykPath) {
        return String.join("/", ykPath.split("::"));
    }

    public static boolean isJavaYkPath(String ykPath) {
        return ykPath.startsWith("java::");
    }

    /**
     * Whether {@code ykPath} resolves to a class on the compile classpath (any
     * package, not only {@code java::...}).
     */
    public boolean hasLoadableClass(String ykPath) {
        return loadClass(ykPathToInternal(ykPath)).isPresent();
    }

    /**
     * Classpath subtyping: {@code superTypeYkPath} assignable from
     * {@code subTypeYkPath}
     * (implements / extends per {@link Class#isAssignableFrom}).
     */
    public boolean classpathAssignable(String superTypeYkPath, String subTypeYkPath) {
        Optional<Class<?>> sup = loadClass(ykPathToInternal(superTypeYkPath));
        Optional<Class<?>> sub = loadClass(ykPathToInternal(subTypeYkPath));
        if (sup.isEmpty() || sub.isEmpty()) {
            return false;
        }
        return sup.get().isAssignableFrom(sub.get());
    }

    /**
     * Registers a nominal for this JVM type (if loadable) so elaboration treats
     * it like other class types.
     */
    public void ensureClasspathNominal(TypeTable table, String ykPath) {
        loadClass(ykPathToInternal(ykPath)).ifPresent(cl -> classToTy(cl, table));
    }

    /** Load a class by internal name ({@code org/bukkit/Foo}) or empty. */
    public Optional<Class<?>> loadClassFromInternal(String internalName) {
        return loadClass(internalName);
    }

    /**
     * Yakou typing for a reflected class (primitives, arrays, reference types).
     */
    public Ty reflectTypeToTy(Class<?> c, TypeTable table) {
        return classToTy(c, table);
    }

    /**
     * Instance constructor with the given arity, preferring public then
     * protected. Used for {@code INVOKESPECIAL} to a JVM superclass.
     */
    public Optional<Constructor<?>> findInstanceConstructor(Class<?> clazz, int arity) {
        List<Constructor<?>> matches = new ArrayList<>();
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            if (ctor.getParameterCount() != arity) {
                continue;
            }
            int mod = ctor.getModifiers();
            if (!Modifier.isPublic(mod) && !Modifier.isProtected(mod)) {
                continue;
            }
            matches.add(ctor);
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() == 1) {
            return Optional.of(matches.getFirst());
        }
        Optional<Constructor<?>> pub = matches.stream().filter(c -> Modifier.isPublic(c.getModifiers())).findFirst();
        return pub.or(() -> Optional.of(matches.getFirst()));
    }

    /**
     * {@code use}-imported short name (e.g. {@code System}) → static field chain
     * {@code System::out} → type of field.
     */
    public Optional<Ty> resolveImportedStaticPath(TypeTable table, List<String> segments) {
        if (segments.size() < 2) {
            return Optional.empty();
        }
        Optional<String> start = table.javaInternalName(segments.getFirst());
        if (start.isEmpty()) {
            return Optional.empty();
        }
        Optional<Class<?>> clazz = loadClass(start.get());
        if (clazz.isEmpty()) {
            return Optional.empty();
        }
        Class<?> cur = clazz.get();
        for (int i = 1; i < segments.size(); i++) {
            String seg = segments.get(i);
            Field f;
            try {
                f = cur.getField(seg);
            } catch (NoSuchFieldException e) {
                return Optional.empty();
            }
            if (!Modifier.isStatic(f.getModifiers())) {
                return Optional.empty();
            }
            Class<?> ft = f.getType();
            if (i == segments.size() - 1) {
                return Optional.of(classToTy(ft, table));
            }
            if (!ft.isPrimitive() && !ft.isArray()) {
                cur = ft;
            } else {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Public instance field type (non-static), or empty if missing / not Java /
     * static.
     */
    public Optional<Ty> instanceFieldType(String ykClassPath, String fieldName, TypeTable table) {
        if (!hasLoadableClass(ykClassPath)) {
            return Optional.empty();
        }
        String internal = ykPathToInternal(ykClassPath);
        Optional<Class<?>> oc = loadClass(internal);
        if (oc.isEmpty()) {
            return Optional.empty();
        }
        Class<?> c = oc.get();
        Field f;
        try {
            f = c.getField(fieldName);
        } catch (NoSuchFieldException e) {
            return Optional.empty();
        }
        if (Modifier.isStatic(f.getModifiers())) {
            return Optional.empty();
        }
        return Optional.of(classToTy(f.getType(), table));
    }

    /**
     * Instance method including {@code self} as first parameter.
     * {@code extraArgCount} is JVM
     * parameter count (not including receiver). {@code -1} = only if exactly one
     * overload.
     */
    public Optional<Ty.FunTy> instanceMethod(
            String ykClassPath, String methodName, int extraArgCount, TypeTable table) {
        String internal = ykPathToInternal(ykClassPath);
        Optional<Class<?>> oc = loadClass(internal);
        if (oc.isEmpty()) {
            return Optional.empty();
        }
        Class<?> c = oc.get();
        List<Method> candidates = new ArrayList<>();
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(methodName)) {
                continue;
            }
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            int nargs = m.getParameterCount();
            if (extraArgCount >= 0 && nargs != extraArgCount) {
                continue;
            }
            candidates.add(m);
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (extraArgCount >= 0) {
            Method m = candidates.getFirst();
            return Optional.of(methodToFunTy(m, ykClassPath, table));
        }
        if (candidates.size() == 1) {
            return Optional.of(methodToFunTy(candidates.getFirst(), ykClassPath, table));
        }
        return Optional.empty();
    }

    /**
     * Static method on a Java class imported as a nominal type, e.g.
     * {@code System::gc()} or
     * {@code Integer::parseInt("1")}.
     *
     * <p>
     * Overload resolution is strict: resolves only when {@code extraArgCount}
     * matches exactly
     * (or {@code -1} and there is exactly one overload).
     * </p>
     */
    public Optional<Ty.FunTy> staticMethod(
            String ykClassPath, String methodName, int extraArgCount, TypeTable table) {
        String internal = ykPathToInternal(ykClassPath);
        Optional<Class<?>> oc = loadClass(internal);
        if (oc.isEmpty()) {
            return Optional.empty();
        }
        Class<?> c = oc.get();
        List<Method> candidates = new ArrayList<>();
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(methodName)) {
                continue;
            }
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            int nargs = m.getParameterCount();
            if (extraArgCount >= 0 && nargs != extraArgCount) {
                continue;
            }
            candidates.add(m);
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (extraArgCount >= 0) {
            Method m = candidates.getFirst();
            return Optional.of(staticMethodToFunTy(m, table));
        }
        if (candidates.size() == 1) {
            return Optional.of(staticMethodToFunTy(candidates.getFirst(), table));
        }
        return Optional.empty();
    }

    private Ty.FunTy staticMethodToFunTy(Method m, TypeTable table) {
        List<Ty> params = new ArrayList<>();
        for (Class<?> p : m.getParameterTypes()) {
            params.add(classToTy(p, table));
        }
        Ty ret = classToTy(m.getReturnType(), table);
        return new Ty.FunTy(params, ret);
    }

    private Optional<Class<?>> loadClass(String internalName) {
        String dot = internalName.replace('/', '.');
        try {
            return Optional.of(loader.loadClass(dot));
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return Optional.empty();
        }
    }

    private Ty.FunTy methodToFunTy(Method m, String ykClassPath, TypeTable table) {
        Ty self = Ty.nom(ykClassPath, List.of());
        List<Ty> params = new ArrayList<>();
        params.add(self);
        for (Class<?> p : m.getParameterTypes()) {
            params.add(classToTy(p, table));
        }
        Ty ret = classToTy(m.getReturnType(), table);
        return new Ty.FunTy(params, ret);
    }

    private Ty classToTy(Class<?> c, TypeTable table) {
        if (c == void.class || c == Void.class) {
            return Ty.prim(Prim.UNIT);
        }
        if (c == boolean.class) {
            return Ty.prim(Prim.BOOL);
        }
        if (c == byte.class) {
            return Ty.prim(Prim.I8);
        }
        if (c == short.class) {
            return Ty.prim(Prim.I16);
        }
        if (c == char.class) {
            return Ty.prim(Prim.I32);
        }
        if (c == int.class) {
            return Ty.prim(Prim.I32);
        }
        if (c == long.class) {
            return Ty.prim(Prim.I64);
        }
        if (c == float.class) {
            return Ty.prim(Prim.F32);
        }
        if (c == double.class) {
            return Ty.prim(Prim.F64);
        }
        if (c.isArray()) {
            return Ty.arrayOf(classToTy(c.getComponentType(), table));
        }
        if (String.class.equals(c)) {
            return Ty.prim(Prim.STR);
        }
        String internal = c.getName().replace('.', '/');
        String yk = internalNameToYkPath(internal);
        table.registerNominal(yk);
        return Ty.nom(yk, List.of());
    }
}
