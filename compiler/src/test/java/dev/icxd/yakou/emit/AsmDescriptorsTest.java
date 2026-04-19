package dev.icxd.yakou.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.icxd.yakou.ast.TypeRef;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class AsmDescriptorsTest {

        @Test
        void javaGenericTypeErasesToRawDescriptor() {
                TypeRef list = new TypeRef.Applied(
                                new TypeRef.Path(List.of("java", "util", "List"), Optional.empty()),
                                List.of(new TypeRef.Path(List.of("java", "lang", "String"), Optional.empty())),
                                Optional.empty());
                assertEquals("Ljava/util/List;", AsmDescriptors.typeRefToField(list));
        }

        @Test
        void nestedJavaGenericErasesToRawDescriptor() {
                TypeRef inner = new TypeRef.Applied(
                                new TypeRef.Path(List.of("java", "util", "List"), Optional.empty()),
                                List.of(new TypeRef.Path(List.of("java", "lang", "String"), Optional.empty())),
                                Optional.empty());
                TypeRef map = new TypeRef.Applied(
                                new TypeRef.Path(List.of("java", "util", "Map"), Optional.empty()),
                                List.of(
                                                new TypeRef.Path(List.of("java", "lang", "String"), Optional.empty()),
                                                inner),
                                Optional.empty());
                assertEquals("Ljava/util/Map;", AsmDescriptors.typeRefToField(map));
        }

        @Test
        void yakouStrArrayDescriptorMatchesJvmStringArray() {
                TypeRef sa = new TypeRef.Array(new TypeRef.Named("str", Optional.empty()), Optional.empty());
                assertEquals("[Ljava/lang/String;", AsmDescriptors.typeRefToField(sa));
        }
}
