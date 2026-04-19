package dev.icxd.yakou.resolve;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.Optional;

public record ResolveIssue(Kind kind, String message, Optional<SourceSpan> span) {

    public enum Kind {
        DUPLICATE,
        UNRESOLVED,
    }

    public static ResolveIssue duplicate(String name, Optional<SourceSpan> span) {
        return new ResolveIssue(
                Kind.DUPLICATE, "duplicate definition: '" + name + "'", span);
    }

    public static ResolveIssue unresolved(String name, Optional<SourceSpan> span) {
        return new ResolveIssue(Kind.UNRESOLVED, "unresolved name: '" + name + "'", span);
    }

    public static ResolveIssue unresolvedPath(String path, Optional<SourceSpan> span) {
        return new ResolveIssue(Kind.UNRESOLVED, "unresolved path: '" + path + "'", span);
    }

    public static ResolveIssue unresolvedMember(
            String owner, String member, Optional<SourceSpan> span) {
        return new ResolveIssue(
                Kind.UNRESOLVED,
                "unresolved member '" + member + "' on '" + owner + "'",
                span);
    }
}
