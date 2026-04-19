package dev.icxd.yakou.typeck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TypeResult {

    private final List<TypeIssue> issues = new ArrayList<>();

    public void add(TypeIssue issue) {
        issues.add(issue);
    }

    public List<TypeIssue> issues() {
        return Collections.unmodifiableList(issues);
    }

    public boolean ok() {
        return issues.isEmpty();
    }
}
