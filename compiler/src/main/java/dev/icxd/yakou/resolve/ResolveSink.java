package dev.icxd.yakou.resolve;

import java.util.ArrayList;
import java.util.List;

/** Collects resolution diagnostics. */
public final class ResolveSink {

    private final List<ResolveIssue> issues = new ArrayList<>();

    public void add(ResolveIssue issue) {
        issues.add(issue);
    }

    public List<ResolveIssue> issues() {
        return List.copyOf(issues);
    }

    public boolean ok() {
        return issues.isEmpty();
    }
}
