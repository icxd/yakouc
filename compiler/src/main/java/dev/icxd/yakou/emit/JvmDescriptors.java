package dev.icxd.yakou.emit;

import dev.icxd.yakou.typeck.JavaInterop;
import dev.icxd.yakou.typeck.Prim;
import dev.icxd.yakou.typeck.Ty;

/** JVM descriptors from elaborated {@link Ty}. */
public final class JvmDescriptors {

    private JvmDescriptors() {
    }

    public static String fieldDescriptor(Ty ty) {
        Ty d = Ty.deref(ty);
        return switch (d) {
            case Ty.ArrayTy at -> "[" + componentFieldDescriptor(at.element());
            case Ty.PrimTy p -> primDescriptor(p.prim());
            case Ty.NomTy n -> {
                if (JavaInterop.isJavaYkPath(n.path())) {
                    yield AsmDescriptors.typeRefToField(
                            new dev.icxd.yakou.ast.TypeRef.Path(
                                    java.util.List.of(n.path().split("::")),
                                    java.util.Optional.empty()));
                }
                yield "L" + JvmInternal.qualified(n.path()) + ";";
            }
            case Ty.VarTy ignored -> "Ljava/lang/Object;";
            case Ty.FunTy ignored ->
                throw new CodegenException("function type cannot appear as field descriptor: " + ty);
        };
    }

    /**
     * Component type inside {@code […]} (same as {@link #fieldDescriptor} but
     * without array prefix).
     */
    private static String componentFieldDescriptor(Ty ty) {
        Ty d = Ty.deref(ty);
        if (d instanceof Ty.ArrayTy nested) {
            return fieldDescriptor(d);
        }
        return fieldDescriptor(ty);
    }

    public static String methodDescriptor(java.util.List<Ty> params, Ty ret) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (Ty p : params) {
            sb.append(fieldDescriptor(p));
        }
        sb.append(')');
        sb.append(returnDescriptor(ret));
        return sb.toString();
    }

    public static String returnDescriptor(Ty ret) {
        Ty d = Ty.deref(ret);
        if (d instanceof Ty.PrimTy pp && pp.prim() == Prim.UNIT) {
            return "V";
        }
        return fieldDescriptor(ret);
    }

    private static String primDescriptor(Prim p) {
        return switch (p) {
            case I8, I16, I32, U16, U32 -> "I";
            case I64 -> "J";
            case F32 -> "F";
            case F64 -> "D";
            case BOOL -> "Z";
            case STR -> "Ljava/lang/String;";
            case UNIT -> "V";
            case BYTES -> "[B";
        };
    }
}
