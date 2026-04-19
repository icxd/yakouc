package dev.icxd.yakou.ls;

import dev.icxd.yakou.ast.Block;
import dev.icxd.yakou.ast.ClassMethod;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.ast.FnBody;
import dev.icxd.yakou.ast.Stmt;
import dev.icxd.yakou.ast.Item;
import dev.icxd.yakou.syntax.SourceSpan;

import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import dev.icxd.yakou.typeck.Ty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class YakouInlayHints {

    private static final Pattern LET_NAME = Pattern.compile("let\\s+(\\w+)");

    private YakouInlayHints() {
    }

    static List<InlayHint> hints(InlayHintParams params, String text, YakouServerState state) {
        String uri = params.getTextDocument().getUri();
        Optional<YakouAnalysis.Result> ar = YakouAnalysis.analyze(uri, text, state);
        if (ar.isEmpty()) {
            return List.of();
        }
        Range want = params.getRange();
        List<InlayHint> out = new ArrayList<>();
        walkLetsInItems(ar.get().ast().items(), text, ar.get().exprTypes(), want, out);
        return out;
    }

    private static void walkLetsInItems(
            List<Item> items, String text, Map<Expr, Ty> types, Range range, List<InlayHint> out) {
        for (Item it : items) {
            switch (it) {
                case Item.Fn fn -> walkLetsInFn(fn.body(), text, types, range, out);
                case Item.Class cl -> {
                    for (ClassMethod m : cl.methods()) {
                        walkLetsInFn(m.body(), text, types, range, out);
                    }
                }
                case Item.Pkg pkg -> walkLetsInItems(pkg.items(), text, types, range, out);
                default -> {
                }
            }
        }
    }

    private static void walkLetsInFn(FnBody body, String text, Map<Expr, Ty> types, Range range, List<InlayHint> out) {
        switch (body) {
            case FnBody.BlockBody bb -> walkLetsInBlock(bb.block(), text, types, range, out);
            case FnBody.ExprEquals ee -> walkLetsInExpr(ee.expr(), text, types, range, out);
        }
    }

    private static void walkLetsInBlock(Block block, String text, Map<Expr, Ty> types, Range range,
            List<InlayHint> out) {
        for (Stmt st : block.statements()) {
            if (st instanceof Stmt.Let let) {
                if (let.annotation().isEmpty()) {
                    Ty t = types.get(let.value());
                    if (t != null) {
                        endOfLetBindingName(text, let)
                                .filter(pos -> positionInRange(range, pos))
                                .ifPresent(
                                        pos -> {
                                            InlayHint h = new InlayHint();
                                            h.setPosition(pos);
                                            h.setKind(InlayHintKind.Type);
                                            h.setLabel(": " + Ty.deref(t));
                                            h.setPaddingLeft(true);
                                            out.add(h);
                                        });
                    }
                }
                walkLetsInExpr(let.value(), text, types, range, out);
            } else if (st instanceof Stmt.ExprSemi es) {
                walkLetsInExpr(es.expr(), text, types, range, out);
            }
        }
        block.trailingExpr().ifPresent(e -> walkLetsInExpr(e, text, types, range, out));
    }

    private static void walkLetsInExpr(Expr expr, String text, Map<Expr, Ty> types, Range range, List<InlayHint> out) {
        switch (expr) {
            case Expr.BlockExpr be -> walkLetsInBlock(be.block(), text, types, range, out);
            case Expr.If i -> {
                walkLetsInExpr(i.cond(), text, types, range, out);
                walkLetsInBlock(i.thenBlock(), text, types, range, out);
                walkLetsInBlock(i.elseBlock(), text, types, range, out);
            }
            case Expr.When w -> {
                walkLetsInExpr(w.scrutinee(), text, types, range, out);
                for (var arm : w.arms()) {
                    walkLetsInBlock(arm.body(), text, types, range, out);
                }
            }
            default -> {
            }
        }
    }

    /**
     * End of the binding identifier in {@code let x …} (position for inlay
     * insertion).
     */
    private static Optional<Position> endOfLetBindingName(String text, Stmt.Let let) {
        Optional<SourceSpan> spOpt = let.span();
        if (spOpt.isEmpty()) {
            return Optional.empty();
        }
        SourceSpan sp = spOpt.get();
        int from = sp.start().offset();
        int to = Math.min(sp.endExclusive().offset(), text.length());
        if (from > to || from < 0) {
            return Optional.empty();
        }
        String slice = text.substring(from, to);
        Matcher m = LET_NAME.matcher(slice);
        if (!m.find()) {
            return Optional.empty();
        }
        int nameEnd = from + m.end(1);
        return Optional.of(LspPositions.offsetToPosition(text, nameEnd));
    }

    private static boolean positionInRange(Range range, Position pos) {
        if (range == null) {
            return true;
        }
        int pl = pos.getLine();
        int pc = pos.getCharacter();
        int sl = range.getStart().getLine();
        int sc = range.getStart().getCharacter();
        int el = range.getEnd().getLine();
        int ec = range.getEnd().getCharacter();
        if (pl < sl || pl > el) {
            return false;
        }
        if (pl == sl && pc < sc) {
            return false;
        }
        if (pl == el && pc >= ec) {
            return false;
        }
        return true;
    }
}
