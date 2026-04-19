package dev.icxd.yakou.resolve;

import java.util.List;

public record ResolveResult(List<ResolveIssue> issues) {

    public boolean ok() {
        return issues.isEmpty();
    }
}
