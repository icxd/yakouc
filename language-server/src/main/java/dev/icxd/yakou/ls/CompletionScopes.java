package dev.icxd.yakou.ls;

import dev.icxd.yakou.ast.AstFile;
import dev.icxd.yakou.ast.Block;
import dev.icxd.yakou.ast.ClassMethod;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.ast.FnBody;
import dev.icxd.yakou.ast.Item;
import dev.icxd.yakou.ast.Param;
import dev.icxd.yakou.ast.Stmt;
import dev.icxd.yakou.ast.WhenArm;
import dev.icxd.yakou.syntax.SourceSpan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Collects identifier names visible at a source offset using lexical scope
 * rules on
 * the AST (best-effort when resolve/typecheck fail).
 */
final class CompletionScopes {

    private CompletionScopes() {
    }

    /**
     * Visible binding names (locals, params), plus same-file fn/class/use shortcuts
     * with
     * kinds suitable for completion icons.
     */
    static List<ScopedName> visible(AstFile ast, int offset) {
        LinkedHashSet<ScopedName> out = new LinkedHashSet<>();
        for (Item it : ast.items()) {
            addTopLevelName(it, out);
        }
        searchItems(ast.items(), offset, out);
        return new ArrayList<>(out);
    }

    private static void addTopLevelName(Item it, LinkedHashSet<ScopedName> out) {
        switch (it) {
            case Item.Fn f -> out.add(ScopedName.of(f.name(), Kind.FUNCTION));
            case Item.Class c -> out.add(ScopedName.of(c.name(), Kind.CLASS));
            case Item.Use u -> {
                if (!u.path().isEmpty()) {
                    out.add(ScopedName.of(u.path().get(u.path().size() - 1), Kind.MODULE));
                }
            }
            default -> {
            }
        }
    }

    private static void searchItems(List<Item> items, int offset, LinkedHashSet<ScopedName> out) {
        for (Item it : items) {
            switch (it) {
                case Item.Fn fn -> {
                    if (containsEnclosing(offset, fn.span(), fn.body())) {
                        collectFn(fn.params(), fn.body(), offset, out);
                        return;
                    }
                }
                case Item.Class cl -> {
                    if (contains(offset, cl.span())) {
                        Optional<ClassMethod> cm = LsAstMethods.innermostEnclosingClassMethod(cl, offset);
                        if (cm.isPresent()) {
                            addClassMemberNames(cl, out);
                            collectFn(cm.get().params(), cm.get().body(), offset, out);
                            return;
                        }
                        addClassMemberNames(cl, out);
                        return;
                    }
                }
                case Item.Pkg pkg -> {
                    if (contains(offset, pkg.span())) {
                        searchItems(pkg.items(), offset, out);
                        return;
                    }
                }
                default -> {
                }
            }
        }
    }

    private static boolean containsEnclosing(int offset, Optional<SourceSpan> itemSpan, FnBody body) {
        if (contains(offset, LsAstMethods.bodySpan(body))) {
            return true;
        }
        return contains(offset, itemSpan);
    }

    /**
     * Class name for constructor calls. Instance fields and methods are completed
     * after
     * {@code self.} / receiver {@code .} via member completion, not as bare
     * identifiers.
     */
    private static void addClassMemberNames(Item.Class cl, LinkedHashSet<ScopedName> out) {
        out.add(ScopedName.of(cl.name(), Kind.CLASS));
    }

    private static void collectFn(List<Param> params, FnBody body, int offset, LinkedHashSet<ScopedName> out) {
        for (Param p : params) {
            out.add(ScopedName.of(p.name(), Kind.VARIABLE));
        }
        switch (body) {
            case FnBody.BlockBody bb -> collectBlock(bb.block(), offset, out);
            case FnBody.ExprEquals ee -> collectExpr(ee.expr(), offset, out);
        }
    }

    private static void collectBlock(Block block, int offset, LinkedHashSet<ScopedName> out) {
        if (!possiblyInside(block.span(), offset)) {
            return;
        }
        for (Stmt st : block.statements()) {
            Optional<SourceSpan> sp = stmtSpan(st);
            if (sp.isEmpty()) {
                continue;
            }
            SourceSpan span = sp.get();
            int start = span.start().offset();
            int end = span.endExclusive().offset();
            if (offset < start) {
                return;
            }
            if (offset >= end) {
                if (st instanceof Stmt.Let let) {
                    out.add(ScopedName.of(let.name(), Kind.VARIABLE));
                }
                continue;
            }
            drillStmt(st, offset, out);
            return;
        }
        Optional<Expr> tail = block.trailingExpr();
        if (tail.isPresent()) {
            collectExpr(tail.get(), offset, out);
        }
    }

    private static void drillStmt(Stmt st, int offset, LinkedHashSet<ScopedName> out) {
        switch (st) {
            case Stmt.Let let -> collectExpr(let.value(), offset, out);
            case Stmt.ExprSemi es -> collectExpr(es.expr(), offset, out);
        }
    }

    private static void collectExpr(Expr expr, int offset, LinkedHashSet<ScopedName> out) {
        switch (expr) {
            case Expr.BlockExpr be -> collectBlock(be.block(), offset, out);
            case Expr.If i -> {
                if (contains(offset, i.cond().span())) {
                    collectExpr(i.cond(), offset, out);
                } else if (contains(offset, i.thenBlock().span())) {
                    collectBlock(i.thenBlock(), offset, out);
                } else {
                    collectBlock(i.elseBlock(), offset, out);
                }
            }
            case Expr.When w -> {
                if (contains(offset, w.scrutinee().span())) {
                    collectExpr(w.scrutinee(), offset, out);
                    return;
                }
                for (WhenArm arm : w.arms()) {
                    if (contains(offset, arm.body().span())) {
                        for (String b : arm.bindNames()) {
                            out.add(ScopedName.of(b, Kind.VARIABLE));
                        }
                        collectBlock(arm.body(), offset, out);
                        return;
                    }
                }
            }
            case Expr.Binary b -> {
                boolean inL = contains(offset, b.left().span());
                boolean inR = contains(offset, b.right().span());
                if (inL) {
                    collectExpr(b.left(), offset, out);
                } else if (inR) {
                    collectExpr(b.right(), offset, out);
                }
            }
            case Expr.Call c -> {
                if (contains(offset, c.callee().span())) {
                    collectExpr(c.callee(), offset, out);
                    return;
                }
                for (Expr a : c.args()) {
                    if (contains(offset, a.span())) {
                        collectExpr(a, offset, out);
                        return;
                    }
                }
            }
            case Expr.Member m -> {
                if (contains(offset, m.receiver().span())) {
                    collectExpr(m.receiver(), offset, out);
                }
            }
            case Expr.Index ix -> {
                if (contains(offset, ix.target().span())) {
                    collectExpr(ix.target(), offset, out);
                } else if (contains(offset, ix.index().span())) {
                    collectExpr(ix.index(), offset, out);
                }
            }
            case Expr.TypeApply ta -> {
                if (contains(offset, ta.base().span())) {
                    collectExpr(ta.base(), offset, out);
                }
            }
            default -> {
            }
        }
    }

    private static Optional<SourceSpan> stmtSpan(Stmt st) {
        return switch (st) {
            case Stmt.Let let -> let.span();
            case Stmt.ExprSemi es -> es.span();
        };
    }

    private static boolean possiblyInside(Optional<SourceSpan> blockSpan, int offset) {
        if (blockSpan.isEmpty()) {
            return true;
        }
        return contains(offset, blockSpan);
    }

    private static boolean contains(int offset, Optional<SourceSpan> span) {
        if (span.isEmpty()) {
            return false;
        }
        SourceSpan s = span.get();
        int a = s.start().offset();
        int b = s.endExclusive().offset();
        return offset >= a && offset < b;
    }

    enum Kind {
        VARIABLE,
        FUNCTION,
        CLASS,
        MODULE,
    }

    record ScopedName(String name, Kind kind) {
        static ScopedName of(String name, Kind kind) {
            return new ScopedName(name, kind);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ScopedName sn && name.equals(sn.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }
}
