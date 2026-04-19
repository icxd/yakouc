package dev.icxd.yakou.typeck;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.Optional;

public record TypeIssue(String message, Optional<SourceSpan> span) {

    public static TypeIssue of(String message, Optional<SourceSpan> span) {
        return new TypeIssue(message, span);
    }
}
