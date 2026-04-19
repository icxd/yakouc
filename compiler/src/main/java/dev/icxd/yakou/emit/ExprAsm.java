package dev.icxd.yakou.emit;

import dev.icxd.yakou.ast.BinaryOp;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.ast.Item;
import dev.icxd.yakou.ast.WhenArm;
import dev.icxd.yakou.typeck.ElabContext;
import dev.icxd.yakou.typeck.JavaInterop;
import dev.icxd.yakou.typeck.Prim;
import dev.icxd.yakou.typeck.Scheme;
import dev.icxd.yakou.typeck.Ty;
import dev.icxd.yakou.typeck.TypeTable;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Emits JVM bytecode for Yakou expressions (calls, {@code when}, Java interop,
 * etc.).
 */
final class ExprAsm {

    final MethodVisitor mv;
    private final String ownerInternal;
    private final EmitContext emit;
    private final ElabContext elab;
    final LocalTable locals;

    ExprAsm(
            MethodVisitor mv,
            String ownerInternal,
            EmitContext emit,
            ElabContext elab,
            LocalTable locals) {
        this.mv = mv;
        this.ownerInternal = ownerInternal;
        this.emit = emit;
        this.elab = elab;
        this.locals = locals;
    }

    void emitExpr(Expr e) {
        e.span().ifPresent(sp -> {
            Label L = new Label();
            mv.visitLabel(L);
            mv.visitLineNumber(sp.start().line(), L);
        });
        switch (e) {
            case Expr.Literal lit -> emitLiteral(lit);
            case Expr.Path p -> emitPath(p);
            case Expr.Binary b -> emitBinary(b);
            case Expr.If i -> emitIfExpr(i);
            case Expr.BlockExpr be -> BlockAsm.emitBlockExpr(this, be);
            case Expr.Call c -> emitCall(c);
            case Expr.When w -> emitWhen(w);
            case Expr.Member m -> emitMemberValue(m);
            case Expr.TypeApply ignored ->
                throw new CodegenException("bare type-apply expression is not supported in codegen");
            case Expr.Index ix -> emitIndex(ix);
        }
    }

    private void emitLiteral(Expr.Literal lit) {
        switch (lit.kind()) {
            case INT -> {
                Ty t = requireType(lit);
                Ty d = emit.unifier.applyFully(t);
                if (d instanceof Ty.PrimTy pp && pp.prim() == Prim.I64) {
                    mv.visitLdcInsn(Long.parseLong(lit.text()));
                } else {
                    pushInt(Integer.parseInt(lit.text()));
                }
            }
            case BOOL -> mv.visitInsn(lit.text().equals("true") ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            case STRING -> mv.visitLdcInsn(unquoteString(lit.text()));
        }
    }

    private static String unquoteString(String text) {
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private void pushInt(int v) {
        switch (v) {
            case -1 -> mv.visitInsn(Opcodes.ICONST_M1);
            case 0 -> mv.visitInsn(Opcodes.ICONST_0);
            case 1 -> mv.visitInsn(Opcodes.ICONST_1);
            case 2 -> mv.visitInsn(Opcodes.ICONST_2);
            case 3 -> mv.visitInsn(Opcodes.ICONST_3);
            case 4 -> mv.visitInsn(Opcodes.ICONST_4);
            case 5 -> mv.visitInsn(Opcodes.ICONST_5);
            default -> {
                if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
                    mv.visitIntInsn(Opcodes.BIPUSH, v);
                } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
                    mv.visitIntInsn(Opcodes.SIPUSH, v);
                } else {
                    mv.visitLdcInsn(v);
                }
            }
        }
    }

    private void emitIndex(Expr.Index ix) {
        Ty recvT = requireType(ix.target());
        Ty rt = emit.unifier.applyFully(recvT);
        if (!(rt instanceof Ty.ArrayTy ar)) {
            throw new CodegenException("indexing requires array type, got " + recvT);
        }
        emitExpr(ix.target());
        emitExprCoerced(ix.index(), Ty.prim(Prim.I32));
        mv.visitInsn(arrayLoadOpcode(ar.element()));
    }

    private static int arrayLoadOpcode(Ty element) {
        Ty d = Ty.deref(element);
        if (d instanceof Ty.PrimTy p) {
            return switch (p.prim()) {
                case BOOL, I8, BYTES -> Opcodes.BALOAD;
                case I16 -> Opcodes.SALOAD;
                case I32, U16, U32 -> Opcodes.IALOAD;
                case I64 -> Opcodes.LALOAD;
                case F32 -> Opcodes.FALOAD;
                case F64 -> Opcodes.DALOAD;
                case STR, UNIT -> Opcodes.AALOAD;
            };
        }
        return Opcodes.AALOAD;
    }

    private void emitPath(Expr.Path p) {
        if (p.segments().size() == 1) {
            String name = p.segments().getFirst();
            Integer slot = locals.slot(name);
            if (slot != null) {
                Ty t = requireType(p);
                TyStack sk = TyStack.of(t);
                mv.visitVarInsn(sk.loadOpcode(), slot);
                return;
            }
        }
        if (emit.javaInterop.resolveImportedStaticPath(emit.table, p.segments()).isPresent()) {
            JavaEmit.emitStaticFieldChain(mv, emit.javaInterop, emit.table, p.segments());
            return;
        }
        throw new CodegenException("unsupported path expression: " + p.segments());
    }

    private void emitBinary(Expr.Binary b) {
        Ty tl = requireType(b.left());
        Ty tr = requireType(b.right());
        if (b.op() == BinaryOp.PLUS && TyStack.isString(tl) && TyStack.isString(tr)) {
            emitExpr(b.left());
            emitExpr(b.right());
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/String",
                    "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    false);
            return;
        }
        if (TyStack.isIntLike(tl) && TyStack.isIntLike(tr)) {
            emitExprCoerced(b.left(), tl);
            emitExprCoerced(b.right(), tr);
            emitIntegralBinary(b.op(), tl);
            return;
        }
        if (b.op() == BinaryOp.EQEQ || b.op() == BinaryOp.NEQ) {
            emitExpr(b.left());
            emitExpr(b.right());
            emitRefEqualityAsBool(b.op());
            return;
        }
        throw new CodegenException("unsupported binary expression: " + b.op() + " on " + tl + ", " + tr);
    }

    private void emitIntCompareAsBool(BinaryOp op) {
        Label trueL = new Label();
        Label endL = new Label();
        switch (op) {
            case GE -> mv.visitJumpInsn(Opcodes.IF_ICMPGE, trueL);
            case LE -> mv.visitJumpInsn(Opcodes.IF_ICMPLE, trueL);
            case LT -> mv.visitJumpInsn(Opcodes.IF_ICMPLT, trueL);
            case GT -> mv.visitJumpInsn(Opcodes.IF_ICMPGT, trueL);
            default -> throw new IllegalStateException();
        }
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitJumpInsn(Opcodes.GOTO, endL);
        mv.visitLabel(trueL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitLabel(endL);
    }

    private void emitIntegralBinary(BinaryOp op, Ty operandTy) {
        Ty d = emit.unifier.applyFully(operandTy);
        boolean isLong = d instanceof Ty.PrimTy pp && pp.prim() == Prim.I64;
        switch (op) {
            case PLUS -> mv.visitInsn(isLong ? Opcodes.LADD : Opcodes.IADD);
            case MINUS -> mv.visitInsn(isLong ? Opcodes.LSUB : Opcodes.ISUB);
            case MUL -> mv.visitInsn(isLong ? Opcodes.LMUL : Opcodes.IMUL);
            case DIV -> mv.visitInsn(isLong ? Opcodes.LDIV : Opcodes.IDIV);
            case GE, LE, LT, GT -> emitIntegralCompareAsBool(op, isLong);
            case EQEQ, NEQ -> emitIntegralEqualityAsBool(op, isLong);
            default -> throw new CodegenException("unsupported integral op: " + op);
        }
    }

    private void emitIntegralCompareAsBool(BinaryOp op, boolean isLong) {
        if (!isLong) {
            emitIntCompareAsBool(op);
            return;
        }
        // For longs: (a,b) -> LCMP => int on stack => branch
        mv.visitInsn(Opcodes.LCMP);
        Label trueL = new Label();
        Label endL = new Label();
        switch (op) {
            case GE -> mv.visitJumpInsn(Opcodes.IFGE, trueL);
            case LE -> mv.visitJumpInsn(Opcodes.IFLE, trueL);
            case LT -> mv.visitJumpInsn(Opcodes.IFLT, trueL);
            case GT -> mv.visitJumpInsn(Opcodes.IFGT, trueL);
            default -> throw new IllegalStateException();
        }
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitJumpInsn(Opcodes.GOTO, endL);
        mv.visitLabel(trueL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitLabel(endL);
    }

    private void emitIntegralEqualityAsBool(BinaryOp op, boolean isLong) {
        Label trueL = new Label();
        Label endL = new Label();
        if (isLong) {
            mv.visitInsn(Opcodes.LCMP);
            if (op == BinaryOp.EQEQ) {
                mv.visitJumpInsn(Opcodes.IFEQ, trueL);
            } else {
                mv.visitJumpInsn(Opcodes.IFNE, trueL);
            }
        } else {
            if (op == BinaryOp.EQEQ) {
                mv.visitJumpInsn(Opcodes.IF_ICMPEQ, trueL);
            } else {
                mv.visitJumpInsn(Opcodes.IF_ICMPNE, trueL);
            }
        }
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitJumpInsn(Opcodes.GOTO, endL);
        mv.visitLabel(trueL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitLabel(endL);
    }

    private void emitRefEqualityAsBool(BinaryOp op) {
        // Reference equality for now (strict/cheap). In the future this can become
        // structural.
        Label trueL = new Label();
        Label endL = new Label();
        if (op == BinaryOp.EQEQ) {
            mv.visitJumpInsn(Opcodes.IF_ACMPEQ, trueL);
        } else {
            mv.visitJumpInsn(Opcodes.IF_ACMPNE, trueL);
        }
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitJumpInsn(Opcodes.GOTO, endL);
        mv.visitLabel(trueL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitLabel(endL);
    }

    private void emitIfExpr(Expr.If i) {
        Ty condT = requireType(i.cond());
        if (!TyStack.isIntLike(condT)) {
            throw new CodegenException("if condition must be bool, got " + condT);
        }
        Label elseL = new Label();
        Label endL = new Label();
        emitExpr(i.cond());
        mv.visitJumpInsn(Opcodes.IFEQ, elseL);
        BlockAsm.emitBlockValue(this, i.thenBlock(), locals);
        mv.visitJumpInsn(Opcodes.GOTO, endL);
        mv.visitLabel(elseL);
        BlockAsm.emitBlockValue(this, i.elseBlock(), locals);
        mv.visitLabel(endL);
    }

    private void emitMemberValue(Expr.Member m) {
        Ty recvT = requireType(m.receiver());
        Ty rd = emit.unifier.applyFully(recvT);
        if (rd instanceof Ty.ArrayTy && "length".equals(m.name())) {
            emitExpr(m.receiver());
            mv.visitInsn(Opcodes.ARRAYLENGTH);
            return;
        }
        if (rd instanceof Ty.NomTy nn && emit.yakouClasses.contains(nn.path())) {
            Optional<Ty> fld = emit.table.fieldType(nn.path(), m.name());
            if (fld.isPresent()) {
                emitExpr(m.receiver());
                mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        JvmInternal.qualified(nn.path()),
                        m.name(),
                        JvmDescriptors.fieldDescriptor(fld.get()));
                return;
            }
        }
        if (rd instanceof Ty.NomTy jn && emit.javaInterop.hasLoadableClass(jn.path())) {
            emitExpr(m.receiver());
            JavaEmit.emitInstanceFieldGet(mv, emit.javaInterop, jn.path(), m.name());
            return;
        }
        throw new CodegenException("unsupported member expression (not a Yakou field): " + m.name());
    }

    private void emitCall(Expr.Call c) {
        if (c.callee() instanceof Expr.Member mem) {
            emitMemberCall(mem, c.args());
            return;
        }
        if (c.callee() instanceof Expr.TypeApply ta) {
            if (!(ta.base() instanceof Expr.Path)) {
                throw new CodegenException("constructor type-apply needs a path base");
            }
            String qual = CodegenPaths.qualifyExprPath((Expr.Path) ta.base(), elab);
            emitConstructorNew(c, qual);
            return;
        }
        if (c.callee() instanceof Expr.Path path) {
            String qual = CodegenPaths.qualifyExprPath(path, elab);
            if (path.segments().size() == 2) {
                // Java static method call on imported short name: System::gc(),
                // Integer::parseInt("1")
                String start = path.segments().getFirst();
                Optional<String> internal = emit.table.javaInternalName(start);
                if (internal.isPresent()) {
                    String javaPath = dev.icxd.yakou.typeck.JavaInterop.internalNameToYkPath(internal.get());
                    String methodName = path.segments().getLast();
                    List<Ty> argTys = new ArrayList<>();
                    for (Expr a : c.args()) {
                        argTys.add(requireType(a));
                    }
                    JavaEmit.emitStaticCall(
                            mv,
                            emit.javaInterop,
                            emit.table,
                            javaPath,
                            methodName,
                            argTys,
                            () -> {
                                for (Expr a : c.args()) {
                                    emitExpr(a);
                                }
                            });
                    return;
                }
            }
            if (emit.table.lookupConstructor(qual).isPresent()) {
                emitConstructorNew(c, qual);
                return;
            }
            Item.Fn modFn = emit.moduleFunctions.get(qual);
            if (modFn != null) {
                Scheme rawSch = emit.table.lookupValueQualified(qual).orElseThrow();
                Ty.FunTy ft = (Ty.FunTy) emit.unifier.applyFully(rawSch.instantiate(emit.unifier));
                emitExprsCoerced(c.args(), ft.params());
                mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        ownerInternal,
                        JvmClassNames.mangleMethodName(qual),
                        JvmDescriptors.methodDescriptor(ft.params(), ft.ret()),
                        false);
                return;
            }
            int idx = qual.lastIndexOf("::");
            if (idx > 0) {
                String ownerPath = qual.substring(0, idx);
                String methodName = qual.substring(idx + 2);
                if (emit.yakouClasses.contains(ownerPath)) {
                    Scheme rawM = emit.table.lookupClassMethod(ownerPath, methodName).orElseThrow();
                    Ty.FunTy ft = (Ty.FunTy) emit.unifier.applyFully(rawM.instantiate(emit.unifier));
                    emitExprsCoerced(c.args(), ft.params());
                    List<Ty> ps = ft.params();
                    mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            JvmInternal.qualified(ownerPath),
                            methodName,
                            JvmDescriptors.methodDescriptor(ps, ft.ret()),
                            false);
                    return;
                }
            }
        }
        throw new CodegenException("unsupported call form");
    }

    private void emitConstructorNew(Expr.Call call, String classPath) {
        TypeTable.ConstructorSpec cs = emit.table.lookupConstructor(classPath).orElseThrow();
        Ty ret = emit.unifier.applyFully(requireType(call));
        List<Ty> classArgs;
        if (ret instanceof Ty.NomTy nom && nom.path().equals(classPath)) {
            classArgs = nom.typeArgs();
        } else {
            throw new CodegenException(
                    "expected constructor call result type " + classPath + "[...], got " + ret);
        }
        Ty ctorFnTy = TypeTable.instantiateConstructorFunctionType(cs, classArgs);
        Ty.FunTy ctorFun = (Ty.FunTy) emit.unifier.applyFully(ctorFnTy);
        List<Expr> args = call.args();
        String internal = JvmInternal.qualified(classPath);
        mv.visitTypeInsn(Opcodes.NEW, internal);
        mv.visitInsn(Opcodes.DUP);
        emitExprsCoerced(args, ctorFun.params());
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                internal,
                "<init>",
                JvmDescriptors.methodDescriptor(ctorFun.params(), Ty.prim(dev.icxd.yakou.typeck.Prim.UNIT)),
                false);
    }

    private void emitExprsCoerced(List<Expr> args, List<Ty> expectedParams) {
        if (args.size() != expectedParams.size()) {
            throw new CodegenException("argument count mismatch for call");
        }
        for (int i = 0; i < args.size(); i++) {
            emitExprCoerced(args.get(i), expectedParams.get(i));
        }
    }

    private void emitExprCoerced(Expr e, Ty expected) {
        emitExpr(e);
        Ty got = requireType(e);
        Ty ge = emit.unifier.applyFully(got);
        Ty ex = emit.unifier.applyFully(expected);
        if (ge instanceof Ty.PrimTy gp && ex instanceof Ty.PrimTy ep) {
            if (gp.prim() == Prim.I32 && ep.prim() == Prim.I64) {
                mv.visitInsn(Opcodes.I2L);
            }
            if (gp.prim() == Prim.I64 && ep.prim() == Prim.I32) {
                mv.visitInsn(Opcodes.L2I);
            }
        }
    }

    private void emitMemberCall(Expr.Member mem, List<Expr> args) {
        Ty recvT = requireType(mem.receiver());
        Ty rd = emit.unifier.applyFully(recvT);
        if (rd instanceof Ty.NomTy tn && emit.table.isTrait(tn.path())) {
            Optional<Scheme> msch = emit.table.lookupClassMethod(tn.path(), mem.name());
            if (msch.isPresent()) {
                Ty.FunTy ft = (Ty.FunTy) emit.unifier.applyFully(msch.get().instantiate(emit.unifier));
                List<Ty> tail = ft.params().subList(1, ft.params().size());
                if (tail.size() != args.size()) {
                    throw new CodegenException("wrong arg count for " + mem.name());
                }
                emitExpr(mem.receiver());
                mv.visitTypeInsn(Opcodes.CHECKCAST, JvmInternal.qualified(tn.path()));
                emitExprsCoerced(args, tail);
                mv.visitMethodInsn(
                        Opcodes.INVOKEINTERFACE,
                        JvmInternal.qualified(tn.path()),
                        mem.name(),
                        JvmDescriptors.methodDescriptor(tail, ft.ret()),
                        true);
                return;
            }
        }
        if (rd instanceof Ty.NomTy nn && emit.yakouClasses.contains(nn.path())) {
            Optional<Scheme> msch = emit.table.lookupClassMethod(nn.path(), mem.name());
            if (msch.isPresent()) {
                Ty.FunTy ft = (Ty.FunTy) emit.unifier.applyFully(msch.get().instantiate(emit.unifier));
                List<Ty> tail = ft.params().subList(1, ft.params().size());
                if (tail.size() != args.size()) {
                    throw new CodegenException("wrong arg count for " + mem.name());
                }
                emitExpr(mem.receiver());
                mv.visitTypeInsn(Opcodes.CHECKCAST, JvmInternal.qualified(nn.path()));
                emitExprsCoerced(args, tail);
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        JvmInternal.qualified(nn.path()),
                        mem.name(),
                        JvmDescriptors.methodDescriptor(tail, ft.ret()),
                        false);
                return;
            }
        }
        List<Ty> argTys = new ArrayList<>();
        for (Expr a : args) {
            argTys.add(requireType(a));
        }
        JavaEmit.emitVirtualCall(
                mv,
                emit.javaInterop,
                emit.table,
                recvT,
                mem.name(),
                argTys,
                () -> emitExpr(mem.receiver()),
                () -> {
                    for (Expr a : args) {
                        emitExpr(a);
                    }
                });
    }

    private void emitWhen(Expr.When w) {
        Ty st = requireType(w.scrutinee());
        emitExpr(w.scrutinee());
        locals.bindLocal("$when", st);
        int scrSlot = locals.slot("$when");
        mv.visitVarInsn(TyStack.of(st).storeOpcode(), scrSlot);

        Ty sd = emit.unifier.applyFully(st);
        if (!(sd instanceof Ty.NomTy gn)) {
            throw new CodegenException("when scrutinee must be nominal");
        }
        Optional<String> sealedOpt;
        if (emit.table.isSealedTrait(gn.path())) {
            sealedOpt = Optional.of(gn.path());
        } else {
            sealedOpt = emit.table.sealedTraitForVariantClass(gn.path());
        }
        if (sealedOpt.isEmpty()) {
            throw new CodegenException("when requires sealed trait scrutinee type");
        }
        String sealedTrait = sealedOpt.get();

        Label end = new Label();
        for (WhenArm arm : w.arms()) {
            Optional<String> variantClass = resolveArmVariant(sealedTrait, arm.variant());
            if (variantClass.isEmpty()) {
                throw new CodegenException("unknown when variant '" + arm.variant() + "'");
            }
            String vc = variantClass.get();
            mv.visitVarInsn(Opcodes.ALOAD, scrSlot);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, JvmInternal.qualified(vc));
            Label nextArm = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, nextArm);

            mv.visitVarInsn(Opcodes.ALOAD, scrSlot);
            mv.visitTypeInsn(Opcodes.CHECKCAST, JvmInternal.qualified(vc));

            Item.Class variantAst = emit.yakouClassAst.get(vc);
            if (variantAst == null) {
                throw new CodegenException("missing AST for variant class " + vc);
            }
            TypeTable.ConstructorSpec cs = emit.table.lookupConstructor(vc).orElseThrow();
            Ty.FunTy ctorFun = (Ty.FunTy) emit.unifier.applyFully(cs.scheme().instantiate(emit.unifier));
            List<Ty> fields = ctorFun.params();
            if (fields.size() != arm.bindNames().size()) {
                throw new CodegenException("when bindings mismatch for " + arm.variant());
            }

            Ty castTy = Ty.nom(vc, gn.typeArgs());
            locals.bindLocal("$wcast", castTy);
            int castSlot = locals.slot("$wcast");
            mv.visitVarInsn(TyStack.of(castTy).storeOpcode(), castSlot);

            for (int i = 0; i < fields.size(); i++) {
                String fname = variantAst.ctorFields().get(i).name();
                Ty ft = fields.get(i);
                mv.visitVarInsn(Opcodes.ALOAD, castSlot);
                mv.visitFieldInsn(
                        Opcodes.GETFIELD,
                        JvmInternal.qualified(vc),
                        fname,
                        JvmDescriptors.fieldDescriptor(ft));
                locals.bindLocal(arm.bindNames().get(i), ft);
                int bindSlot = locals.slot(arm.bindNames().get(i));
                mv.visitVarInsn(TyStack.of(ft).storeOpcode(), bindSlot);
            }

            BlockAsm.emitBlockValue(this, arm.body(), locals);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(nextArm);
        }
        mv.visitLabel(end);
    }

    private Optional<String> resolveArmVariant(String sealedTrait, String armVariant) {
        for (String vc : emit.table.sealedVariantClasses(sealedTrait)) {
            if (TypeTable.lastPathSegment(vc).equals(armVariant)) {
                return Optional.of(vc);
            }
        }
        return Optional.empty();
    }

    Ty requireType(Expr e) {
        Ty t = emit.exprTypes.get(e);
        if (t == null) {
            throw new CodegenException("missing inferred type for expression");
        }
        return t;
    }
}
