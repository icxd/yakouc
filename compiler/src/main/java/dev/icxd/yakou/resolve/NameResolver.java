package dev.icxd.yakou.resolve;

import dev.icxd.yakou.ast.Attribute;
import dev.icxd.yakou.ast.AstFile;
import dev.icxd.yakou.ast.Block;
import dev.icxd.yakou.ast.ClassMethod;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.ast.FieldParam;
import dev.icxd.yakou.ast.FnBody;
import dev.icxd.yakou.ast.Item;
import dev.icxd.yakou.ast.Param;
import dev.icxd.yakou.ast.Stmt;
import dev.icxd.yakou.ast.TypeRef;
import dev.icxd.yakou.ast.WhenArm;
import dev.icxd.yakou.syntax.SourceSpan;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Two-pass name resolution: populate scopes, then resolve paths, types, and
 * member access.
 *
 * <p>
 * Java {@code use} imports are treated as opaque roots: any {@code ::} suffix
 * is accepted.
 */
public final class NameResolver {

    private static final Set<String> PRIMITIVE_TYPES = Set.of("i8", "i16", "i32", "i64", "u16", "u32", "f32", "f64",
            "str", "bool", "unit", "bytes");

    private final ResolveSink issues = new ResolveSink();
    /** Class member scopes for {@code self.field} checks. */
    private final IdentityHashMap<Item.Class, Scope> classMemberScopes = new IdentityHashMap<>();

    public ResolveResult resolve(AstFile file) {
        Scope root = new Scope(null);
        for (Item it : file.items()) {
            defineItem(root, it);
        }
        for (Item it : file.items()) {
            resolveItem(root, it);
        }
        return new ResolveResult(issues.issues());
    }

    // --- Pass 1: definitions ---

    private void defineItem(Scope scope, Item item) {
        switch (item) {
            case Item.Use u -> defineUse(scope, u);
            case Item.Trait t -> scope.define(t.name(), new Binding.Trait(t), t.span(), issues);
            case Item.Class c -> defineClass(scope, c);
            case Item.Pkg p -> definePackage(scope, p);
            case Item.Fn f -> scope.define(f.name(), new Binding.Fn(f), f.span(), issues);
        }
    }

    private void defineUse(Scope scope, Item.Use u) {
        if (u.path().isEmpty()) {
            return;
        }
        String last = u.path().getLast();
        scope.define(last, new Binding.Import(List.copyOf(u.path())), u.span(), issues);
    }

    private void definePackage(Scope scope, Item.Pkg p) {
        Scope inner = new Scope(scope);
        for (Item child : p.items()) {
            defineItem(inner, child);
        }
        scope.define(p.name(), new Binding.Package(p.name(), inner), p.span(), issues);
    }

    private void defineClass(Scope scope, Item.Class c) {
        Scope members = new Scope(scope);
        for (var tp : c.typeParams()) {
            members.define(tp.name(), new Binding.TypeParam(tp.name()), tp.span(), issues);
        }
        for (FieldParam f : c.ctorFields()) {
            members.define(f.name(), new Binding.Field(f), f.span(), issues);
        }
        for (ClassMethod m : c.methods()) {
            members.define(m.name(), new Binding.Method(m, c), m.span(), issues);
        }
        scope.define(c.name(), new Binding.Class(c, members), c.span(), issues);
        classMemberScopes.put(c, members);
    }

    // --- Pass 2: resolve bodies ---

    private void resolveItem(Scope scope, Item item) {
        switch (item) {
            case Item.Use ignored -> {
            }
            case Item.Trait t -> resolveTrait(scope, t);
            case Item.Class c -> resolveClass(scope, c);
            case Item.Pkg p -> resolvePackage(scope, p);
            case Item.Fn f -> resolveFn(scope, f);
        }
    }

    private void resolveTrait(Scope scope, Item.Trait t) {
        for (Attribute a : t.attributes()) {
            resolveExpr(scope, Optional.empty(), a.invocation());
        }
        Scope traitScope = new Scope(scope);
        for (var tp : t.typeParams()) {
            traitScope.define(tp.name(), new Binding.TypeParam(tp.name()), tp.span(), issues);
        }
        for (var tp : t.typeParams()) {
            tp.upperBound().ifPresent(ty -> resolveType(traitScope, ty));
        }
        for (var sig : t.methods()) {
            Scope mscope = new Scope(traitScope);
            for (Param pa : sig.params()) {
                pa.type().ifPresent(ty -> resolveType(mscope, ty));
            }
            resolveType(mscope, sig.returnType());
        }
    }

    private void resolvePackage(Scope scope, Item.Pkg p) {
        for (Attribute a : p.attributes()) {
            resolveExpr(scope, Optional.empty(), a.invocation());
        }
        scope.lookup(p.name())
                .filter(Binding.Package.class::isInstance)
                .map(Binding.Package.class::cast)
                .ifPresentOrElse(
                        pkg -> {
                            for (Item ch : p.items()) {
                                resolveItem(pkg.nested(), ch);
                            }
                        },
                        () -> issues.add(ResolveIssue.unresolved(p.name(), p.span())));
    }

    private void resolveClass(Scope outer, Item.Class c) {
        Optional<Binding> b = outer.lookup(c.name());
        if (b.isEmpty() || !(b.get() instanceof Binding.Class bc)) {
            return;
        }
        Scope members = bc.members();
        for (Attribute a : c.attributes()) {
            resolveExpr(outer, Optional.empty(), a.invocation());
        }
        for (var tp : c.typeParams()) {
            tp.upperBound().ifPresent(ty -> resolveType(members, ty));
        }
        c.superRef().ifPresent(s -> {
            resolveType(members, s.type());
            for (Expr e : s.args()) {
                resolveExpr(members, Optional.of(c), e);
            }
        });
        for (ClassMethod m : c.methods()) {
            for (Attribute a : m.attributes()) {
                resolveExpr(members, Optional.of(c), a.invocation());
            }
            Scope mscope = new Scope(members);
            for (Param pa : m.params()) {
                pa.type().ifPresent(ty -> resolveType(mscope, ty));
                defineParam(mscope, pa);
            }
            resolveType(mscope, m.returnType());
            resolveFnBody(mscope, Optional.of(c), m.body());
        }
    }

    private void resolveFn(Scope scope, Item.Fn f) {
        for (Attribute a : f.attributes()) {
            resolveExpr(scope, Optional.empty(), a.invocation());
        }
        Scope fscope = new Scope(scope);
        for (Param pa : f.params()) {
            pa.type().ifPresent(ty -> resolveType(fscope, ty));
            defineParam(fscope, pa);
        }
        resolveType(fscope, f.returnType());
        resolveFnBody(fscope, Optional.empty(), f.body());
    }

    private void defineParam(Scope scope, Param p) {
        scope.define(p.name(), new Binding.Param(p.name()), p.span(), issues);
    }

    private void resolveFnBody(Scope scope, Optional<Item.Class> selfClass, FnBody body) {
        switch (body) {
            case FnBody.BlockBody bb -> resolveBlock(scope, selfClass, bb.block());
            case FnBody.ExprEquals ee -> resolveExpr(scope, selfClass, ee.expr());
        }
    }

    private void resolveBlock(Scope scope, Optional<Item.Class> selfClass, Block block) {
        Scope bscope = new Scope(scope);
        for (Stmt st : block.statements()) {
            switch (st) {
                case Stmt.Let l -> {
                    l.annotation().ifPresent(ty -> resolveType(bscope, ty));
                    resolveExpr(bscope, selfClass, l.value());
                    bscope.define(l.name(), new Binding.LocalLet(l.name()), l.span(), issues);
                }
                case Stmt.ExprSemi es -> resolveExpr(bscope, selfClass, es.expr());
            }
        }
        block.trailingExpr().ifPresent(e -> resolveExpr(bscope, selfClass, e));
    }

    private void resolveExpr(Scope scope, Optional<Item.Class> selfClass, Expr e) {
        switch (e) {
            case Expr.Literal ignored -> {
            }
            case Expr.Path p -> resolvePath(scope, p.segments(), p.span());
            case Expr.TypeApply ta -> {
                resolveExpr(scope, selfClass, ta.base());
                for (TypeRef tr : ta.typeArgs()) {
                    resolveType(scope, tr);
                }
            }
            case Expr.Index ix -> {
                resolveExpr(scope, selfClass, ix.target());
                resolveExpr(scope, selfClass, ix.index());
            }
            case Expr.Binary b -> {
                resolveExpr(scope, selfClass, b.left());
                resolveExpr(scope, selfClass, b.right());
            }
            case Expr.Member m -> resolveMember(scope, selfClass, m);
            case Expr.Call c -> {
                resolveExpr(scope, selfClass, c.callee());
                for (Expr a : c.args()) {
                    resolveExpr(scope, selfClass, a);
                }
            }
            case Expr.If i -> {
                resolveExpr(scope, selfClass, i.cond());
                resolveBlock(scope, selfClass, i.thenBlock());
                resolveBlock(scope, selfClass, i.elseBlock());
            }
            case Expr.When w -> {
                resolveExpr(scope, selfClass, w.scrutinee());
                for (WhenArm arm : w.arms()) {
                    Scope armScope = new Scope(scope);
                    for (String bind : arm.bindNames()) {
                        armScope.define(bind, new Binding.LocalLet(bind), arm.span(), issues);
                    }
                    resolveBlock(armScope, selfClass, arm.body());
                }
            }
            case Expr.BlockExpr be -> resolveBlock(scope, selfClass, be.block());
        }
    }

    private void resolveMember(Scope scope, Optional<Item.Class> selfClass, Expr.Member m) {
        resolveExpr(scope, selfClass, m.receiver());
        if (m.receiver() instanceof Expr.Path rp
                && rp.segments().size() == 1
                && rp.segments().getFirst().equals("self")) {
            // Declared fields/methods live in classMemberScopes; JVM-inherited APIs
            // (e.g. JavaPlugin#getLogger) are validated in typecheck only.
            return;
        }
        // Java interop: imported root — accept member chain
        if (m.receiver() instanceof Expr.Path rp && rp.segments().size() == 1) {
            Optional<Binding> root = scope.lookup(rp.segments().getFirst());
            if (root.isPresent() && root.get() instanceof Binding.Import) {
                return;
            }
        }
    }

    private void resolvePath(Scope scope, List<String> segments, Optional<SourceSpan> span) {
        if (segments.isEmpty()) {
            return;
        }
        String first = segments.getFirst();
        Optional<Binding> b = scope.lookup(first);
        if (b.isEmpty()) {
            issues.add(ResolveIssue.unresolved(first, span));
            return;
        }
        if (segments.size() == 1) {
            return;
        }
        resolvePathSuffix(b.get(), segments.subList(1, segments.size()), span);
    }

    private void resolvePathSuffix(Binding head, List<String> rest, Optional<SourceSpan> span) {
        if (rest.isEmpty()) {
            return;
        }
        switch (head) {
            case Binding.Import ignored -> {
                // java::… — remainder is opaque
            }
            case Binding.Package pkg -> {
                Optional<Binding> next = pkg.nested().getLocal(rest.getFirst());
                if (next.isEmpty()) {
                    issues.add(ResolveIssue.unresolved(rest.getFirst(), span));
                    return;
                }
                if (rest.size() == 1) {
                    return;
                }
                resolvePathSuffix(next.get(), rest.subList(1, rest.size()), span);
            }
            case Binding.Class cls -> {
                Optional<Binding> mem = cls.members().getLocal(rest.getFirst());
                if (mem.isEmpty()) {
                    issues.add(ResolveIssue.unresolvedMember(cls.item().name(), rest.getFirst(), span));
                    return;
                }
                if (rest.size() == 1) {
                    return;
                }
                resolvePathSuffix(mem.get(), rest.subList(1, rest.size()), span);
            }
            case Binding.Method ignored ->
                issues.add(ResolveIssue.unresolvedPath("cannot qualify past method", span));
            case Binding.Fn ignored ->
                issues.add(ResolveIssue.unresolvedPath(String.join("::", rest), span));
            default ->
                issues.add(ResolveIssue.unresolvedPath(String.join("::", rest), span));
        }
    }

    private void resolveType(Scope scope, TypeRef ty) {
        switch (ty) {
            case TypeRef.Named n -> {
                if (PRIMITIVE_TYPES.contains(n.name())) {
                    break;
                }
                if (scope.lookup(n.name()).isEmpty()) {
                    issues.add(ResolveIssue.unresolved(n.name(), n.span()));
                }
            }
            case TypeRef.Path p -> {
                if (p.segments().isEmpty()) {
                    return;
                }
                resolvePath(scope, p.segments(), p.span());
            }
            case TypeRef.Applied a -> {
                resolveType(scope, a.base());
                for (TypeRef arg : a.args()) {
                    resolveType(scope, arg);
                }
            }
            case TypeRef.Array ar -> resolveType(scope, ar.element());
        }
    }
}
