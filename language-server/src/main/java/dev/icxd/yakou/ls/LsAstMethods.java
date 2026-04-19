package dev.icxd.yakou.ls;

import dev.icxd.yakou.ast.ClassMethod;
import dev.icxd.yakou.ast.FnBody;
import dev.icxd.yakou.ast.Item;
import dev.icxd.yakou.syntax.SourceSpan;

import java.util.List;
import java.util.Optional;

/** Shared {@link ClassMethod} / {@link FnBody} span helpers for LS features. */
final class LsAstMethods {

    private LsAstMethods() {
    }

    static Optional<SourceSpan> bodySpan(FnBody body) {
        return switch (body) {
            case FnBody.BlockBody bb -> bb.block().span();
            case FnBody.ExprEquals ee -> ee.expr().span();
        };
    }

    static boolean offsetInBody(FnBody body, int offset) {
        return contains(bodySpan(body), offset);
    }

    /**
     * True if the cursor is inside the method’s body span, or (when the parser left
     * no span) inside the body as a fallback.
     */
    static boolean touchesClassMethod(ClassMethod m, int offset) {
        if (offsetInBody(m.body(), offset)) {
            return true;
        }
        return contains(m.span(), offset);
    }

    /**
     * The class method whose span tightly encloses the offset (smallest span wins).
     */
    static Optional<ClassMethod> innermostEnclosingClassMethod(Item.Class cl, int offset) {
        ClassMethod best = null;
        int bestLen = Integer.MAX_VALUE;
        for (ClassMethod m : cl.methods()) {
            if (!touchesClassMethod(m, offset)) {
                continue;
            }
            int len = m.span()
                    .map(
                            s -> s.endExclusive().offset()
                                    - s.start().offset())
                    .orElse(Integer.MAX_VALUE);
            if (len < bestLen) {
                bestLen = len;
                best = m;
            }
        }
        return Optional.ofNullable(best);
    }

    static boolean contains(Optional<SourceSpan> span, int offset) {
        if (span.isEmpty()) {
            return false;
        }
        SourceSpan s = span.get();
        return offset >= s.start().offset() && offset < s.endExclusive().offset();
    }
}
