package dev.icxd.yakou.resolve;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Lexical scope for value / type / module names. */
public final class Scope {

    private final Scope parent;
    private final Map<String, Binding> bindings = new HashMap<>();

    public Scope(Scope parent) {
        this.parent = parent;
    }

    public Scope parent() {
        return parent;
    }

    public void define(
            String name, Binding binding, Optional<SourceSpan> span, ResolveSink issues) {
        if (bindings.containsKey(name)) {
            issues.add(ResolveIssue.duplicate(name, span));
            return;
        }
        bindings.put(name, binding);
    }

    /** Shallow lookup only. */
    public Optional<Binding> getLocal(String name) {
        return Optional.ofNullable(bindings.get(name));
    }

    /** Walks parent chain. */
    public Optional<Binding> lookup(String name) {
        Binding b = bindings.get(name);
        if (b != null) {
            return Optional.of(b);
        }
        if (parent != null) {
            return parent.lookup(name);
        }
        return Optional.empty();
    }
}
