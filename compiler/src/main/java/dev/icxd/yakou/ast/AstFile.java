package dev.icxd.yakou.ast;

import java.util.List;

public record AstFile(String fileName, List<Item> items) {
}
