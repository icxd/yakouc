package dev.icxd.yakou.typeck;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Context for elaborating {@code TypeRef} to {@link Ty} (nominal paths, type
 * params).
 */
public final class ElabContext {

    private final String packagePrefix;
    private final Optional<String> selfSimpleName;
    private final Optional<String> selfQualifiedPath;
    private final Map<String, Ty> typeParams;
    private final Unifier unifier;
    private final TypeTable table;

    public ElabContext(
            String packagePrefix,
            Optional<String> selfSimpleName,
            Optional<String> selfQualifiedPath,
            Map<String, Ty> typeParams,
            Unifier unifier,
            TypeTable table) {
        this.packagePrefix = packagePrefix;
        this.selfSimpleName = selfSimpleName;
        this.selfQualifiedPath = selfQualifiedPath;
        this.typeParams = new HashMap<>(typeParams);
        this.unifier = unifier;
        this.table = table;
    }

    public static ElabContext root(Unifier u, TypeTable table) {
        return new ElabContext("", Optional.empty(), Optional.empty(), Map.of(), u, table);
    }

    public ElabContext withPackage(String pkg) {
        return new ElabContext(pkg, selfSimpleName, selfQualifiedPath, typeParams, unifier, table);
    }

    /** Binds {@code self} for elaboration; keeps existing type-parameter map. */
    public ElabContext withSelf(String simpleClass, String qualifiedClass) {
        return new ElabContext(
                packagePrefix, Optional.of(simpleClass), Optional.of(qualifiedClass), typeParams, unifier, table);
    }

    public ElabContext withTypeParams(Map<String, Ty> extra) {
        Map<String, Ty> m = new HashMap<>(typeParams);
        m.putAll(extra);
        return new ElabContext(
                packagePrefix, selfSimpleName, selfQualifiedPath, m, unifier, table);
    }

    public String packagePrefix() {
        return packagePrefix;
    }

    public Unifier unifier() {
        return unifier;
    }

    public TypeTable table() {
        return table;
    }

    public Optional<Ty> typeParam(String name) {
        return Optional.ofNullable(typeParams.get(name));
    }

    /**
     * Resolves a short type name to a nominal path (primitives and type params
     * handled
     * elsewhere).
     */
    public String qualifyNominal(String shortName) {
        if (selfSimpleName.isPresent() && selfSimpleName.get().equals(shortName)) {
            return selfQualifiedPath.orElse(shortName);
        }
        Optional<String> javaPath = table.javaImportedYkPath(shortName);
        if (javaPath.isPresent()) {
            return javaPath.get();
        }
        String inPkg = packagePrefix.isEmpty() ? shortName : packagePrefix + "::" + shortName;
        if (table.hasNominal(inPkg)) {
            return inPkg;
        }
        if (table.hasNominal(shortName)) {
            return shortName;
        }
        return inPkg;
    }
}
