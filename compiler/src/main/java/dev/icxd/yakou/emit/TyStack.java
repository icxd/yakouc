package dev.icxd.yakou.emit;

import dev.icxd.yakou.typeck.Prim;
import dev.icxd.yakou.typeck.Ty;

/** Operand stack category for locals / returns. */
enum TyStack {
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    REF;

    static TyStack of(Ty t) {
        Ty d = Ty.deref(t);
        if (d instanceof Ty.PrimTy p) {
            return switch (p.prim()) {
                case I8, I16, I32, U16, U32, BOOL -> INT;
                case I64 -> LONG;
                case F32 -> FLOAT;
                case F64 -> DOUBLE;
                case STR -> REF;
                case BYTES -> REF;
                case UNIT ->
                    throw new CodegenException("unit cannot be stored as a local value");
            };
        }
        if (d instanceof Ty.NomTy) {
            return REF;
        }
        if (d instanceof Ty.ArrayTy) {
            return REF;
        }
        if (d instanceof Ty.VarTy) {
            return REF;
        }
        throw new CodegenException("unsupported type for codegen: " + t);
    }

    static boolean isIntLike(Ty t) {
        Ty d = Ty.deref(t);
        if (d instanceof Ty.PrimTy p) {
            return switch (p.prim()) {
                case I8, I16, I32, I64, U16, U32, BOOL -> true;
                default -> false;
            };
        }
        return false;
    }

    static boolean isString(Ty t) {
        Ty d = Ty.deref(t);
        return d instanceof Ty.PrimTy pp && pp.prim() == Prim.STR;
    }

    static boolean isVoid(Ty t) {
        Ty d = Ty.deref(t);
        return d instanceof Ty.PrimTy pp && pp.prim() == Prim.UNIT;
    }

    static void emitReturn(org.objectweb.asm.MethodVisitor mv, Ty ret) {
        Ty d = Ty.deref(ret);
        if (d instanceof Ty.PrimTy p) {
            switch (p.prim()) {
                case UNIT -> mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
                case I8, I16, I32, U16, U32, BOOL -> mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
                case I64 -> mv.visitInsn(org.objectweb.asm.Opcodes.LRETURN);
                case F32 -> mv.visitInsn(org.objectweb.asm.Opcodes.FRETURN);
                case F64 -> mv.visitInsn(org.objectweb.asm.Opcodes.DRETURN);
                case STR, BYTES -> mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
            }
            return;
        }
        if (d instanceof Ty.NomTy) {
            mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
            return;
        }
        if (d instanceof Ty.ArrayTy) {
            mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
            return;
        }
        throw new CodegenException("cannot emit return for " + ret);
    }

    int loadOpcode() {
        return switch (this) {
            case INT -> org.objectweb.asm.Opcodes.ILOAD;
            case LONG -> org.objectweb.asm.Opcodes.LLOAD;
            case FLOAT -> org.objectweb.asm.Opcodes.FLOAD;
            case DOUBLE -> org.objectweb.asm.Opcodes.DLOAD;
            case REF -> org.objectweb.asm.Opcodes.ALOAD;
        };
    }

    int storeOpcode() {
        return switch (this) {
            case INT -> org.objectweb.asm.Opcodes.ISTORE;
            case LONG -> org.objectweb.asm.Opcodes.LSTORE;
            case FLOAT -> org.objectweb.asm.Opcodes.FSTORE;
            case DOUBLE -> org.objectweb.asm.Opcodes.DSTORE;
            case REF -> org.objectweb.asm.Opcodes.ASTORE;
        };
    }

    int localSlots() {
        return this == LONG || this == DOUBLE ? 2 : 1;
    }
}
