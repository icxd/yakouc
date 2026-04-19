package dev.icxd.yakou.syntax;

/** 1-based line/column, 0-based offset into the source string. */
public record SourcePosition(int line, int column, int offset) {
}
