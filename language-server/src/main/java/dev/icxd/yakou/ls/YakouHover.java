package dev.icxd.yakou.ls;

import dev.icxd.yakou.ast.AstFile;
import dev.icxd.yakou.ast.Block;
import dev.icxd.yakou.ast.ClassMethod;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.ast.FieldParam;
import dev.icxd.yakou.ast.FnBody;
import dev.icxd.yakou.ast.Item;
import dev.icxd.yakou.ast.Modifier;
import dev.icxd.yakou.ast.Param;
import dev.icxd.yakou.ast.Stmt;
import dev.icxd.yakou.ast.TypeParam;
import dev.icxd.yakou.ast.TypeRef;
import dev.icxd.yakou.syntax.SourceSpan;

import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import dev.icxd.yakou.typeck.Ty;

import java.util.List;
import java.util.Map;
import java.util.Optional;

final class YakouHover {

    /**
     * Must match VS Code language id (`package.json` contributes {@code yakou}),
     * not {@code yk}.
     */
    private static final String MARKDOWN_LANG = "yakou";

    private YakouHover() {
    }

    static Hover hover(String documentUri, String text, Position pos, YakouServerState state) {
        int offset = LspPositions.utf16Offset(text, pos);
        Optional<YakouAnalysis.Result> ar = YakouAnalysis.analyze(documentUri, text, state);
        if (ar.isEmpty()) {
            return null;
        }
        AstFile ast = ar.get().ast();
        Map<Expr, Ty> types = ar.get().exprTypes();

        Optional<Hover> letH = letDeclHover(ast, text, offset, types);
        if (letH.isPresent()) {
            return letH.get();
        }

        Optional<Hover> exprH = exprTypeHover(types, ast, offset);
        if (exprH.isPresent()) {
            return exprH.get();
        }

        Optional<Hover> fnH = walkFnMethodHeaders(ast.items(), offset);
        if (fnH.isPresent()) {
            return fnH.get();
        }

        Optional<Hover> classH = classItemHoverWalk(ast.items(), offset);
        if (classH.isPresent()) {
            return classH.get();
        }

        return useHoverWalk(ast.items(), offset).orElse(null);
    }

    private static Optional<Hover> exprTypeHover(Map<Expr, Ty> types, AstFile ast, int offset) {
        Optional<Expr> se = HoverExprs.smallestContaining(ast, offset);
        if (se.isEmpty()) {
            return Optional.empty();
        }
        Expr e = se.get();
        Ty ty = types.get(e);
        if (ty == null) {
            return Optional.empty();
        }
        String body = "```" + MARKDOWN_LANG + "\n" + Ty.deref(ty) + "\n```";
        Range r = e.span().map(LspPositions::spanToRange).orElse(null);
        return Optional.of(new Hover(new MarkupContent("markdown", body), r));
    }

    private static Optional<Hover> letDeclHover(
            AstFile ast, String text, int offset, Map<Expr, Ty> types) {
        return walkLetDecl(ast.items(), text, offset, types);
    }

    private static Optional<Hover> walkLetDecl(
            List<Item> items, String text, int offset, Map<Expr, Ty> types) {
        for (Item it : items) {
            Optional<Hover> h = switch (it) {
                case Item.Fn fn -> searchLetInFn(fn.body(), text, offset, types);
                case Item.Class cl -> {
                    Optional<Hover> mh = Optional.empty();
                    for (ClassMethod m : cl.methods()) {
                        mh = searchLetInFn(m.body(), text, offset, types);
                        if (mh.isPresent()) {
                            yield mh;
                        }
                    }
                    yield Optional.empty();
                }
                case Item.Pkg pkg -> walkLetDecl(pkg.items(), text, offset, types);
                default -> Optional.empty();
            };
            if (h.isPresent()) {
                return h;
            }
        }
        return Optional.empty();
    }

    private static Optional<Hover> searchLetInFn(FnBody body, String text, int offset, Map<Expr, Ty> types) {
        return switch (body) {
            case FnBody.BlockBody bb -> searchLetInBlock(bb.block(), text, offset, types);
            case FnBody.ExprEquals ee -> searchLetInExpr(ee.expr(), text, offset, types);
        };
    }

    private static Optional<Hover> searchLetInBlock(Block block, String text, int offset, Map<Expr, Ty> types) {
        if (!containsOffset(block.span(), offset)) {
            return Optional.empty();
        }
        for (Stmt st : block.statements()) {
            if (st instanceof Stmt.Let let && containsOffset(let.span(), offset)) {
                if (offsetInLetHead(text, let, offset)) {
                    return hoverMarkdown(formatLet(let, types), let.span());
                }
                Optional<Hover> inner = searchLetInExpr(let.value(), text, offset, types);
                if (inner.isPresent()) {
                    return inner;
                }
            } else if (st instanceof Stmt.ExprSemi es && exprContainsOffset(es.expr(), offset)) {
                Optional<Hover> inner = searchLetInExpr(es.expr(), text, offset, types);
                if (inner.isPresent()) {
                    return inner;
                }
            }
        }
        Optional<Expr> tail = block.trailingExpr();
        if (tail.isPresent() && exprContainsOffset(tail.get(), offset)) {
            return searchLetInExpr(tail.get(), text, offset, types);
        }
        return Optional.empty();
    }

    /**
     * {@code let} binding / optional type annotation only — stops before {@code =},
     * so hovers on
     * the RHS still get expression types.
     */
    private static boolean offsetInLetHead(String text, Stmt.Let let, int offset) {
        Optional<SourceSpan> sp = let.span();
        if (sp.isEmpty()) {
            return false;
        }
        SourceSpan s = sp.get();
        int from = s.start().offset();
        int to = Math.min(s.endExclusive().offset(), text.length());
        if (offset < from || offset >= to) {
            return false;
        }
        String slice = text.substring(from, to);
        int eq = slice.indexOf('=');
        if (eq < 0) {
            return true;
        }
        return offset < from + eq;
    }

    private static Optional<Hover> searchLetInExpr(Expr expr, String text, int offset, Map<Expr, Ty> types) {
        return switch (expr) {
            case Expr.BlockExpr be -> searchLetInBlock(be.block(), text, offset, types);
            case Expr.If i -> {
                Optional<Hover> h = searchLetInExpr(i.cond(), text, offset, types);
                if (h.isPresent()) {
                    yield h;
                }
                h = searchLetInBlock(i.thenBlock(), text, offset, types);
                if (h.isPresent()) {
                    yield h;
                }
                yield searchLetInBlock(i.elseBlock(), text, offset, types);
            }
            case Expr.When w -> {
                Optional<Hover> h = searchLetInExpr(w.scrutinee(), text, offset, types);
                if (h.isPresent()) {
                    yield h;
                }
                for (var arm : w.arms()) {
                    Optional<Hover> bh = searchLetInBlock(arm.body(), text, offset, types);
                    if (bh.isPresent()) {
                        yield bh;
                    }
                }
                yield Optional.empty();
            }
            default -> Optional.empty();
        };
    }

    private static Optional<Hover> hoverMarkdown(String decl, Optional<SourceSpan> spanOpt) {
        String body = "```" + MARKDOWN_LANG + "\n" + decl + "\n```";
        Range r = spanOpt.map(LspPositions::spanToRange).orElse(null);
        return Optional.of(new Hover(new MarkupContent("markdown", body), r));
    }

    private static String formatLet(Stmt.Let let, Map<Expr, Ty> types) {
        StringBuilder sb = new StringBuilder("let ").append(let.name());
        if (let.annotation().isPresent()) {
            sb.append(": ").append(formatTypeRef(let.annotation().get()));
        } else {
            Ty rhs = types.get(let.value());
            if (rhs != null) {
                sb.append(": ").append(Ty.deref(rhs));
            }
        }
        sb.append(";");
        return sb.toString();
    }

    private static String formatTypeRef(TypeRef tr) {
        return switch (tr) {
            case TypeRef.Named n -> n.name();
            case TypeRef.Path p -> String.join("::", p.segments());
            case TypeRef.Applied a -> {
                StringBuilder sb = new StringBuilder();
                sb.append(formatTypeRef(a.base())).append("[");
                for (int i = 0; i < a.args().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(formatTypeRef(a.args().get(i)));
                }
                sb.append("]");
                yield sb.toString();
            }
            case TypeRef.Array ar -> "[]" + formatTypeRef(ar.element());
        };
    }

    private static Optional<Hover> walkFnMethodHeaders(List<Item> items, int offset) {
        for (Item it : items) {
            Optional<Hover> h = switch (it) {
                case Item.Fn fn -> fnHeaderHover(fn, offset);
                case Item.Class cl -> {
                    if (!containsOffset(cl.span(), offset)) {
                        yield Optional.empty();
                    }
                    yield LsAstMethods.innermostEnclosingClassMethod(cl, offset)
                            .flatMap(m -> methodHeaderHover(m, offset));
                }
                case Item.Pkg pkg -> walkFnMethodHeaders(pkg.items(), offset);
                default -> Optional.empty();
            };
            if (h.isPresent()) {
                return h;
            }
        }
        return Optional.empty();
    }

    private static Optional<Hover> fnHeaderHover(Item.Fn fn, int offset) {
        Optional<Integer> bodyStart = fnBodyStartOffset(fn.body());
        if (bodyStart.isPresent() && offset >= bodyStart.get()) {
            return Optional.empty();
        }
        String sig = formatFnSignature(fn.name(), fn.params(), fn.returnType());
        if (containsOffset(fn.span(), offset)) {
            return hoverMarkdown(sig, fn.span());
        }
        if (fn.span().isEmpty()) {
            return hoverMarkdown(sig, Optional.empty());
        }
        return Optional.empty();
    }

    private static Optional<Hover> methodHeaderHover(ClassMethod m, int offset) {
        Optional<Integer> bodyStart = fnBodyStartOffset(m.body());
        if (bodyStart.isPresent() && offset >= bodyStart.get()) {
            return Optional.empty();
        }
        String sig = formatFnSignature(m.name(), m.params(), m.returnType());
        if (containsOffset(m.span(), offset)) {
            return hoverMarkdown(sig, m.span());
        }
        if (m.span().isEmpty()) {
            return hoverMarkdown(sig, Optional.empty());
        }
        return Optional.empty();
    }

    private static Optional<Integer> fnBodyStartOffset(FnBody body) {
        return switch (body) {
            case FnBody.BlockBody bb -> bb.block().span().map(s -> s.start().offset());
            case FnBody.ExprEquals ee -> ee.expr().span().map(s -> s.start().offset());
        };
    }

    /**
     * Class declaration line / braces between methods — not inside any method body.
     */
    private static Optional<Hover> classItemHoverWalk(List<Item> items, int offset) {
        for (Item it : items) {
            Optional<Hover> h = switch (it) {
                case Item.Class cl -> {
                    if (!containsOffset(cl.span(), offset)) {
                        yield Optional.empty();
                    }
                    if (offsetInAnyClassMethodBody(cl, offset)) {
                        yield Optional.empty();
                    }
                    yield hoverMarkdown(formatClassDecl(cl), cl.span());
                }
                case Item.Pkg pkg -> classItemHoverWalk(pkg.items(), offset);
                default -> Optional.empty();
            };
            if (h.isPresent()) {
                return h;
            }
        }
        return Optional.empty();
    }

    private static boolean offsetInAnyClassMethodBody(Item.Class cl, int offset) {
        for (ClassMethod m : cl.methods()) {
            if (LsAstMethods.offsetInBody(m.body(), offset)) {
                return true;
            }
        }
        return false;
    }

    private static String formatClassDecl(Item.Class c) {
        StringBuilder sb = new StringBuilder();
        for (Modifier mod : c.modifiers()) {
            sb.append(mod.name().toLowerCase(java.util.Locale.ROOT)).append(" ");
        }
        sb.append("class ").append(c.name());
        if (!c.typeParams().isEmpty()) {
            sb.append("[");
            for (int i = 0; i < c.typeParams().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                TypeParam tp = c.typeParams().get(i);
                sb.append(tp.name());
                tp.upperBound().ifPresent(u -> sb.append(": ").append(formatTypeRef(u)));
            }
            sb.append("]");
        }
        sb.append("(");
        List<FieldParam> fields = c.ctorFields();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            FieldParam fp = fields.get(i);
            sb.append(fp.name()).append(": ").append(formatTypeRef(fp.type()));
        }
        sb.append(")");
        c.superRef()
                .ifPresent(
                        sr -> {
                            sb.append(" : ").append(formatTypeRef(sr.type()));
                            if (!sr.args().isEmpty()) {
                                sb.append("(...)");
                            }
                        });
        if (c.stub()) {
            sb.append(";");
        } else {
            sb.append(" { ... }");
        }
        return sb.toString();
    }

    private static String formatFnSignature(String name, List<Param> params, TypeRef ret) {
        StringBuilder sb = new StringBuilder("fn ").append(name).append("(");
        for (int i = 0; i < params.size(); i++) {
            Param p = params.get(i);
            sb.append(p.name());
            if (p.type().isPresent()) {
                sb.append(": ").append(formatTypeRef(p.type().get()));
            }
            if (i + 1 < params.size()) {
                sb.append(", ");
            }
        }
        sb.append("): ").append(formatTypeRef(ret)).append(";");
        return sb.toString();
    }

    private static Optional<Hover> useHoverWalk(List<Item> items, int offset) {
        for (Item it : items) {
            Optional<Hover> h = switch (it) {
                case Item.Use u -> {
                    if (!containsOffset(u.span(), offset)) {
                        yield Optional.empty();
                    }
                    String decl = "use " + String.join("::", u.path()) + ";";
                    yield hoverMarkdown(decl, u.span());
                }
                case Item.Pkg pkg -> useHoverWalk(pkg.items(), offset);
                default -> Optional.empty();
            };
            if (h.isPresent()) {
                return h;
            }
        }
        return Optional.empty();
    }

    private static boolean containsOffset(Optional<SourceSpan> span, int offset) {
        if (span.isEmpty()) {
            return false;
        }
        SourceSpan s = span.get();
        return offset >= s.start().offset() && offset < s.endExclusive().offset();
    }

    private static boolean exprContainsOffset(Expr ex, int offset) {
        return containsOffset(ex.span(), offset);
    }
}
