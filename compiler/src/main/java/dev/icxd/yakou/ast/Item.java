package dev.icxd.yakou.ast;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public sealed interface Item
                permits Item.Use,
                Item.Trait,
                Item.Class,
                Item.Pkg,
                Item.Fn {

        record Use(List<String> path, Optional<SourceSpan> span) implements Item {
        }

        record Trait(
                        List<Attribute> attributes,
                        EnumSet<Modifier> modifiers,
                        String name,
                        List<TypeParam> typeParams,
                        List<MethodSig> methods,
                        Optional<SourceSpan> span)
                        implements Item {
        }

        record Class(
                        List<Attribute> attributes,
                        EnumSet<Modifier> modifiers,
                        String name,
                        List<TypeParam> typeParams,
                        List<FieldParam> ctorFields,
                        Optional<SuperRef> superRef,
                        List<ClassMethod> methods,
                        boolean stub, // `class Foo(...);` without body
                        Optional<SourceSpan> span)
                        implements Item {
        }

        record Pkg(
                        List<Attribute> attributes,
                        EnumSet<Modifier> modifiers,
                        String name,
                        List<Item> items,
                        Optional<SourceSpan> span)
                        implements Item {
        }

        record Fn(
                        List<Attribute> attributes,
                        EnumSet<Modifier> modifiers,
                        String name,
                        List<Param> params,
                        TypeRef returnType,
                        FnBody body,
                        Optional<SourceSpan> span)
                        implements Item {
        }
}
