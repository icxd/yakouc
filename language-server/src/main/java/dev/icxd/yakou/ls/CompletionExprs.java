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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Walks the AST and finds expression nodes for completion. */
final class CompletionExprs {

    private CompletionExprs() {
    }

    /**
     * Expressions whose span ends exactly at {@code dotOffset} (first index of the
     * {@code .} that starts member access).
     */
    static Optional<Expr> receiverExprBeforeDot(AstFile ast, int dotOffset) {
        List<Expr> hits = new ArrayList<>();
        walkFile(ast, e -> {
            if (e.span().isEmpty()) {
                return;
            }
            SourceSpan sp = e.span().get();
            if (sp.endExclusive().offset() == dotOffset) {
                hits.add(e);
            }
        });
        return hits.stream()
                .max(Comparator.comparingInt(e -> spanLength(e.span().orElse(null))));
    }

    private static int spanLength(SourceSpan sp) {
        if (sp == null) {
            return 0;
        }
        return sp.endExclusive().offset() - sp.start().offset();
    }

    static void walkFile(AstFile file, Consumer<Expr> f) {
        walkItems(file.items(), f);
    }

    private static void walkItems(List<Item> items, Consumer<Expr> f) {
        for (Item it : items) {
            walkItem(it, f);
        }
    }

    private static void walkItem(Item it, Consumer<Expr> f) {
        switch (it) {
            case Item.Pkg p -> walkItems(p.items(), f);
            case Item.Fn fn -> walkFnBody(fn.body(), f);
            case Item.Class c -> {
                for (ClassMethod m : c.methods()) {
                    walkFnBody(m.body(), f);
                }
            }
            case Item.Use ignored -> {
            }
            case Item.Trait ignored -> {
            }
        }
    }

    private static void walkFnBody(FnBody body, Consumer<Expr> f) {
        switch (body) {
            case FnBody.BlockBody b -> walkBlock(b.block(), f);
            case FnBody.ExprEquals e -> walkExpr(e.expr(), f);
        }
    }

    private static void walkBlock(Block block, Consumer<Expr> f) {
        for (Stmt s : block.statements()) {
            walkStmt(s, f);
        }
        block.trailingExpr().ifPresent(e -> walkExpr(e, f));
    }

    private static void walkStmt(Stmt s, Consumer<Expr> f) {
        switch (s) {
            case Stmt.Let l -> walkExpr(l.value(), f);
            case Stmt.ExprSemi e -> walkExpr(e.expr(), f);
        }
    }

    private static void walkExpr(Expr e, Consumer<Expr> f) {
        f.accept(e);
        switch (e) {
            case Expr.Literal ignored -> {
            }
            case Expr.Path ignored -> {
            }
            case Expr.Binary b -> {
                walkExpr(b.left(), f);
                walkExpr(b.right(), f);
            }
            case Expr.Member m -> walkExpr(m.receiver(), f);
            case Expr.Call c -> {
                walkExpr(c.callee(), f);
                for (Expr a : c.args()) {
                    walkExpr(a, f);
                }
            }
            case Expr.TypeApply t -> walkExpr(t.base(), f);
            case Expr.Index i -> {
                walkExpr(i.target(), f);
                walkExpr(i.index(), f);
            }
            case Expr.If i -> {
                walkExpr(i.cond(), f);
                walkBlock(i.thenBlock(), f);
                walkBlock(i.elseBlock(), f);
            }
            case Expr.When w -> {
                walkExpr(w.scrutinee(), f);
                for (WhenArm arm : w.arms()) {
                    walkBlock(arm.body(), f);
                }
            }
            case Expr.BlockExpr b -> walkBlock(b.block(), f);
        }
    }
}
