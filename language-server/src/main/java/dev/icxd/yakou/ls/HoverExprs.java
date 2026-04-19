package dev.icxd.yakou.ls;

import dev.icxd.yakou.ast.AstFile;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.syntax.SourceSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class HoverExprs {

    private HoverExprs() {
    }

    /**
     * Innermost expression whose span contains {@code offset} (by smallest source
     * range).
     */
    static Optional<Expr> smallestContaining(AstFile ast, int offset) {
        List<Expr> hits = new ArrayList<>();
        CompletionExprs.walkFile(
                ast,
                e -> {
                    if (e.span().isEmpty()) {
                        return;
                    }
                    SourceSpan sp = e.span().get();
                    int a = sp.start().offset();
                    int b = sp.endExclusive().offset();
                    if (offset >= a && offset < b) {
                        hits.add(e);
                    }
                });
        return hits.stream().min(Comparator.comparingInt(HoverExprs::spanLen));
    }

    private static int spanLen(Expr e) {
        return e.span().map(sp -> sp.endExclusive().offset() - sp.start().offset()).orElse(0);
    }
}
