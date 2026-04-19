package dev.icxd.yakou.resolve;

import dev.icxd.yakou.ast.ClassMethod;
import dev.icxd.yakou.ast.FieldParam;
import dev.icxd.yakou.ast.Item;

import java.util.List;

/** What a simple name refers to after name resolution. */
public sealed interface Binding {

    /**
     * {@code use a::b::C;} — last segment; further {@code ::} segments are Java
     * interop.
     */
    record Import(List<String> fullPath) implements Binding {
    }

    record Package(String name, Scope nested) implements Binding {
    }

    record Trait(Item.Trait item) implements Binding {
    }

    record Class(Item.Class item, Scope members) implements Binding {
    }

    record Fn(Item.Fn item) implements Binding {
    }

    record Method(ClassMethod method, Item.Class owner) implements Binding {
    }

    record TypeParam(String name) implements Binding {
    }

    record Param(String name) implements Binding {
    }

    record Field(FieldParam field) implements Binding {
    }

    record LocalLet(String name) implements Binding {
    }
}
