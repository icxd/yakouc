package dev.icxd.yakou.typeck;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Lexical environment mapping names to type schemes. */
public final class Env {

    private final Env parent;
    private final Map<String, Scheme> bindings = new HashMap<>();

    public Env() {
        this.parent = null;
    }

    private Env(Env parent) {
        this.parent = parent;
    }

    public Env extend() {
        return new Env(this);
    }

    public void put(String name, Scheme scheme) {
        bindings.put(name, scheme);
    }

    public Optional<Scheme> getLocal(String name) {
        return Optional.ofNullable(bindings.get(name));
    }

    public Optional<Scheme> get(String name) {
        Optional<Scheme> l = getLocal(name);
        if (l.isPresent()) {
            return l;
        }
        if (parent != null) {
            return parent.get(name);
        }
        return Optional.empty();
    }

    /** Union of FTV of all schemes in this env chain. */
    public Set<TyVar> ftv() {
        Set<TyVar> s = new HashSet<>();
        for (Scheme sc : bindings.values()) {
            s.addAll(Scheme.ftv(sc));
        }
        if (parent != null) {
            s.addAll(parent.ftv());
        }
        return s;
    }
}
