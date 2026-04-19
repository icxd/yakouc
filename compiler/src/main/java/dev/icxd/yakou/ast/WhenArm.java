package dev.icxd.yakou.ast;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.List;
import java.util.Optional;

/** {@code is Ok(v) { ... }} */
public record WhenArm(String variant, List<String> bindNames, Block body, Optional<SourceSpan> span) {
}
