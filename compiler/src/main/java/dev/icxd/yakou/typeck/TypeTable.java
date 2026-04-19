package dev.icxd.yakou.typeck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Collected value signatures (polymorphic schemes) and nominal type names for
 * elaboration.
 */
public final class TypeTable {

    private final Map<String, Scheme> values = new HashMap<>();
    private final Set<String> nominals = new HashSet<>();
    /** Paths registered via {@code trait} (not classes with the same name). */
    private final Set<String> traits = new HashSet<>();
    /**
     * Last segment of {@code use java::...::Foo} → JVM internal name
     * {@code java/lang/Foo}.
     */
    private final Map<String, String> javaImportShortToInternal = new HashMap<>();
    /** class path → field name → type (monomorphic elaboration snapshot). */
    private final Map<String, Map<String, Ty>> classFields = new HashMap<>();
    /** class path → method name → scheme */
    private final Map<String, Map<String, Scheme>> classMethods = new HashMap<>();
    /**
     * Class constructor: polymorphic scheme + type parameter order for
     * instantiation.
     */
    private final Map<String, ConstructorSpec> constructors = new HashMap<>();
    /**
     * {@code class C: Trait} → {@code C} implements those trait paths (structural
     * subtype). After {@link #finalizeSealedHierarchy()}, includes traits inherited
     * from Yakou superclass chains.
     */
    private final Map<String, Set<String>> classImplementsTrait = new HashMap<>();
    /**
     * Classpath Java interfaces named in {@code class C(): org::…::Iface} when
     * {@code Iface} is a JVM interface (not a superclass).
     */
    private final Map<String, Set<String>> classImplementsJvmIface = new HashMap<>();
    /** Yakou class → immediate Yakou superclass ({@code class Child: Parent}). */
    private final Map<String, String> immediateSuperClass = new HashMap<>();
    private final Set<String> sealedTraits = new HashSet<>();
    /**
     * Sealed trait path → variant class paths (every class that implements that
     * sealed trait, including via superclass), filled in {@link
     * #finalizeSealedHierarchy()}.
     */
    private final Map<String, List<String>> sealedTraitVariantClasses = new HashMap<>();

    /** Constructor signature and the class type variables in AST order. */
    public record ConstructorSpec(Scheme scheme, List<TyVar> typeParametersInOrder) {
    }

    /**
     * Constructor function type after substituting class type parameters (same
     * logic as
     * typechecking for {@code Foo[T](...)} / {@code Foo[i32](...)}).
     */
    public static Ty instantiateConstructorFunctionType(ConstructorSpec cs, List<Ty> classTypeArgs) {
        if (cs.typeParametersInOrder().size() != classTypeArgs.size()) {
            throw new IllegalArgumentException(
                    "expected "
                            + cs.typeParametersInOrder().size()
                            + " class type argument(s), got "
                            + classTypeArgs.size());
        }
        Map<TyVar, Ty> subst = new HashMap<>();
        for (int i = 0; i < classTypeArgs.size(); i++) {
            subst.put(cs.typeParametersInOrder().get(i), classTypeArgs.get(i));
        }
        return Ty.subst(cs.scheme().body(), subst);
    }

    public void registerNominal(String qualifiedPath) {
        nominals.add(qualifiedPath);
    }

    public boolean hasNominal(String path) {
        return nominals.contains(path);
    }

    public void markTrait(String traitPath) {
        traits.add(traitPath);
    }

    public boolean isTrait(String path) {
        return traits.contains(path);
    }

    /** Instance methods declared on the trait (for missing-impl checks). */
    public Set<String> traitMethodNames(String traitPath) {
        Map<String, Scheme> m = classMethods.get(traitPath);
        if (m == null || m.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(m.keySet());
    }

    public void registerValue(String qualifiedName, Scheme scheme) {
        values.put(qualifiedName, scheme);
    }

    public Optional<Scheme> lookupValue(List<String> segments) {
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(values.get(String.join("::", segments)));
    }

    public Optional<Scheme> lookupValueQualified(String qualifiedPath) {
        return Optional.ofNullable(values.get(qualifiedPath));
    }

    public void registerConstructor(String classPath, Scheme scheme, List<TyVar> typeParametersInOrder) {
        constructors.put(classPath, new ConstructorSpec(scheme, new ArrayList<>(typeParametersInOrder)));
        registerValue(classPath, scheme);
    }

    public Optional<ConstructorSpec> lookupConstructor(String classPath) {
        return Optional.ofNullable(constructors.get(classPath));
    }

    /**
     * Records {@code classPath <: traitPath} from the class header {@code : Trait}
     * (direct only; {@link #finalizeSealedHierarchy()} merges superclass traits).
     */
    public void registerImplementsTrait(String classPath, String traitPath) {
        classImplementsTrait.computeIfAbsent(classPath, k -> new HashSet<>()).add(traitPath);
    }

    public void registerJvmInterface(String classPath, String ifaceYkPath) {
        classImplementsJvmIface.computeIfAbsent(classPath, k -> new HashSet<>()).add(ifaceYkPath);
    }

    /** Direct {@code class Hello(): org::…::Iface} clause (classpath interface). */
    public boolean implementsJvmInterface(String classPath, String ifaceYkPath) {
        Set<String> s = classImplementsJvmIface.get(classPath);
        return s != null && s.contains(ifaceYkPath);
    }

    /** {@code classPath} extends this Yakou class as its immediate superclass. */
    public void registerImmediateSuperClass(String classPath, String superClassPath) {
        immediateSuperClass.put(classPath, superClassPath);
    }

    public Optional<String> immediateSuperClass(String classPath) {
        return Optional.ofNullable(immediateSuperClass.get(classPath));
    }

    /**
     * Traits implemented by this Yakou class (including those inherited along
     * superclasses).
     */
    public List<String> traitInterfacesJvmOrder(String classPath) {
        List<String> list = new ArrayList<>();
        list.addAll(classImplementsTrait.getOrDefault(classPath, Set.of()));
        list.addAll(classImplementsJvmIface.getOrDefault(classPath, Set.of()));
        Collections.sort(list);
        return List.copyOf(list);
    }

    /** Whether elaborated {@code class <: trait} was recorded for these paths. */
    public boolean implementsTrait(String classPath, String traitPath) {
        Set<String> s = classImplementsTrait.get(classPath);
        return s != null && s.contains(traitPath);
    }

    public void markTraitSealed(String traitPath) {
        sealedTraits.add(traitPath);
    }

    public boolean isSealedTrait(String traitPath) {
        return sealedTraits.contains(traitPath);
    }

    /**
     * Recompute {@link #implementsTrait} along Yakou class parents, then rebuild
     * sealed-trait → variant class lists so subclasses of a variant are included.
     */
    public void finalizeSealedHierarchy() {
        propagateTransitiveTraitImplementation();
        sealedTraitVariantClasses.clear();
        List<String> classes = new ArrayList<>(constructors.keySet());
        Collections.sort(classes);
        for (String classPath : classes) {
            for (String traitPath : classImplementsTrait.getOrDefault(classPath, Set.of())) {
                if (isSealedTrait(traitPath)) {
                    sealedTraitVariantClasses
                            .computeIfAbsent(traitPath, k -> new ArrayList<>())
                            .add(classPath);
                }
            }
        }
        for (List<String> list : sealedTraitVariantClasses.values()) {
            Collections.sort(list);
        }
    }

    private void propagateTransitiveTraitImplementation() {
        Set<String> classes = new HashSet<>(constructors.keySet());
        Map<String, Set<String>> traits = new HashMap<>();
        for (String c : classes) {
            traits.put(c, new HashSet<>(classImplementsTrait.getOrDefault(c, Set.of())));
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String c : classes) {
                String sup = immediateSuperClass.get(c);
                if (sup == null) {
                    continue;
                }
                Set<String> fromParent = traits.get(sup);
                if (fromParent == null || fromParent.isEmpty()) {
                    continue;
                }
                Set<String> mine = traits.computeIfAbsent(c, k -> new HashSet<>());
                for (String t : fromParent) {
                    if (mine.add(t)) {
                        changed = true;
                    }
                }
            }
        }
        classImplementsTrait.clear();
        for (Map.Entry<String, Set<String>> e : traits.entrySet()) {
            classImplementsTrait.put(e.getKey(), e.getValue());
        }
    }

    public List<String> sealedVariantClasses(String sealedTraitPath) {
        return List.copyOf(sealedTraitVariantClasses.getOrDefault(sealedTraitPath, List.of()));
    }

    /**
     * If {@code variantClassPath} is registered as a variant of a sealed trait,
     * that
     * trait’s path; used for {@code when}.
     */
    public Optional<String> sealedTraitForVariantClass(String variantClassPath) {
        for (Map.Entry<String, List<String>> e : sealedTraitVariantClasses.entrySet()) {
            if (e.getValue().contains(variantClassPath)) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    public static String lastPathSegment(String qualifiedPath) {
        int i = qualifiedPath.lastIndexOf("::");
        return i < 0 ? qualifiedPath : qualifiedPath.substring(i + 2);
    }

    public void registerClassField(String classPath, String fieldName, Ty ty) {
        classFields.computeIfAbsent(classPath, k -> new HashMap<>()).put(fieldName, ty);
    }

    public Optional<Ty> fieldType(String classPath, String field) {
        Map<String, Ty> m = classFields.get(classPath);
        if (m == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(m.get(field));
    }

    public void registerClassMethod(String classPath, String methodName, Scheme scheme) {
        classMethods.computeIfAbsent(classPath, k -> new HashMap<>()).put(methodName, scheme);
    }

    public Optional<Scheme> lookupClassMethod(String classPath, String methodName) {
        Map<String, Scheme> m = classMethods.get(classPath);
        if (m == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(m.get(methodName));
    }

    /**
     * Copies methods registered on {@code traitPath} onto {@code classPath},
     * applying
     * {@code subst} to each scheme. Skips names already present on the class
     * (explicit
     * methods override).
     */
    public void inheritTraitMethods(
            String traitPath, String classPath, UnaryOperator<Scheme> subst) {
        Map<String, Scheme> src = classMethods.get(traitPath);
        if (src == null || src.isEmpty()) {
            return;
        }
        Map<String, Scheme> dst = classMethods.computeIfAbsent(classPath, k -> new HashMap<>());
        for (Map.Entry<String, Scheme> e : src.entrySet()) {
            if (dst.containsKey(e.getKey())) {
                continue;
            }
            dst.put(e.getKey(), subst.apply(e.getValue()));
        }
    }

    /**
     * Registers {@code use} so {@code System::out} resolves via the last segment.
     */
    public void registerJavaImport(List<String> pathSegments) {
        if (pathSegments.isEmpty()) {
            return;
        }
        String last = pathSegments.getLast();
        String internal = String.join("/", pathSegments);
        javaImportShortToInternal.put(last, internal);
    }

    public Optional<String> javaInternalName(String shortName) {
        return Optional.ofNullable(javaImportShortToInternal.get(shortName));
    }

    /**
     * {@code use java::...::Foo} → {@code java::...::Foo} for elaborating type
     * names
     * like {@code Foo}.
     */
    public Optional<String> javaImportedYkPath(String shortName) {
        return javaInternalName(shortName).map(JavaInterop::internalNameToYkPath);
    }
}
