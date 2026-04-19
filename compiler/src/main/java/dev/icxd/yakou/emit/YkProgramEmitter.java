package dev.icxd.yakou.emit;

import dev.icxd.yakou.ast.AstFile;
import dev.icxd.yakou.ast.Block;
import dev.icxd.yakou.ast.ClassMethod;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.ast.FieldParam;
import dev.icxd.yakou.ast.FnBody;
import dev.icxd.yakou.ast.Item;
import dev.icxd.yakou.ast.MethodSig;
import dev.icxd.yakou.ast.Param;
import dev.icxd.yakou.ast.TypeParam;
import dev.icxd.yakou.typeck.ElabContext;
import dev.icxd.yakou.typeck.Elaborator;
import dev.icxd.yakou.typeck.JavaInterop;
import dev.icxd.yakou.typeck.Prim;
import dev.icxd.yakou.typeck.Scheme;
import dev.icxd.yakou.typeck.Ty;
import dev.icxd.yakou.typeck.TypeTable;
import dev.icxd.yakou.typeck.Unifier;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Emits JVM bytecode for traits, Yakou classes, and the module wrapper class.
 */
public final class YkProgramEmitter {

    private record FnEntry(String qualified, Item.Fn fn) {
    }

    private YkProgramEmitter() {
    }

    public static void emit(
            AstFile ast,
            Path sourcePath,
            Path outputDir,
            Map<Expr, Ty> exprTypes,
            TypeTable table,
            Unifier u,
            List<Path> classpath)
            throws IOException {
        JavaInterop java = new JavaInterop(classpath);

        Set<String> yakouClasses = new HashSet<>();
        Map<String, Item.Class> classAst = new HashMap<>();
        collectClasses(ast.items(), "", yakouClasses, classAst);

        List<FnEntry> fns = new ArrayList<>();
        collectFns(ast.items(), "", fns);
        Map<String, Item.Fn> qualifiedFns = new HashMap<>();
        for (FnEntry fe : fns) {
            qualifiedFns.put(fe.qualified, fe.fn);
        }

        String moduleInternal = JvmClassNames.internalNameFromSource(sourcePath);
        String sourceFileName = sourcePath.getFileName().toString();
        EmitContext emitCtx = new EmitContext(
                outputDir,
                sourceFileName,
                moduleInternal,
                exprTypes,
                table,
                u,
                java,
                qualifiedFns,
                yakouClasses,
                classAst);

        emitTraits(ast.items(), "", outputDir, table, u, sourceFileName);
        emitClasses(ast.items(), "", emitCtx);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, moduleInternal, null, "java/lang/Object", null);
        EmitDebug.visitSource(cw, sourceFileName);
        emitDefaultConstructor(cw);

        for (FnEntry fe : fns) {
            emitModuleFn(cw, fe, emitCtx);
        }
        if (fns.stream().anyMatch(f -> "main".equals(f.qualified))) {
            emitJvmMain(cw, moduleInternal);
        }

        cw.visitEnd();
        writeClass(outputDir, moduleInternal, cw.toByteArray());
    }

    private static void emitTraits(
            List<Item> items, String prefix, Path outputDir, TypeTable table, Unifier u, String sourceFileName)
            throws IOException {
        for (Item it : items) {
            switch (it) {
                case Item.Trait t -> emitTraitInterface(t, prefix, outputDir, table, u, sourceFileName);
                case Item.Pkg p ->
                    emitTraits(p.items(), qualify(prefix, p.name()), outputDir, table, u, sourceFileName);
                default -> {
                }
            }
        }
    }

    private static void emitTraitInterface(
            Item.Trait t, String prefix, Path outputDir, TypeTable table, Unifier u, String sourceFileName)
            throws IOException {
        String traitPath = qualify(prefix, t.name());
        String internal = JvmInternal.qualified(traitPath);
        Map<String, Ty> tpMap = freshTypeParams(t.typeParams(), u);
        ElabContext ctx = ElabContext.root(u, table).withPackage(prefix).withTypeParams(tpMap);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                internal,
                null,
                "java/lang/Object",
                null);
        EmitDebug.visitSource(cw, sourceFileName);
        for (MethodSig ms : t.methods()) {
            String desc = traitMethodDescriptor(ms, ctx);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, ms.name(), desc, null, null);
            mv.visitEnd();
        }
        cw.visitEnd();
        writeClass(outputDir, internal, cw.toByteArray());
    }

    private static String traitMethodDescriptor(MethodSig ms, ElabContext ctx) {
        List<Ty> jvmParams = new ArrayList<>();
        for (Param pa : ms.params()) {
            if (pa.type().isEmpty() && "self".equals(pa.name())) {
                continue;
            }
            if (pa.type().isEmpty()) {
                throw new CodegenException("missing type for trait method parameter '" + pa.name() + "'");
            }
            jvmParams.add(Elaborator.elaborate(pa.type().get(), ctx));
        }
        Ty ret = Elaborator.elaborate(ms.returnType(), ctx);
        return JvmDescriptors.methodDescriptor(jvmParams, ret);
    }

    private static void emitClasses(List<Item> items, String prefix, EmitContext emitCtx) throws IOException {
        for (Item it : items) {
            switch (it) {
                case Item.Class c -> emitOneClass(c, prefix, emitCtx);
                case Item.Pkg p -> emitClasses(p.items(), qualify(prefix, p.name()), emitCtx);
                default -> {
                }
            }
        }
    }

    private static void emitOneClass(Item.Class c, String prefix, EmitContext emitCtx) throws IOException {
        TypeTable table = emitCtx.table;
        Unifier u = emitCtx.unifier;
        String classPath = qualify(prefix, c.name());
        String internal = JvmInternal.qualified(classPath);
        Map<String, Ty> tpMap = freshTypeParams(c.typeParams(), u);
        ElabContext pkgCtx = ElabContext.root(u, table).withPackage(prefix).withTypeParams(tpMap);
        ElabContext classCtx = pkgCtx.withSelf(c.name(), classPath);

        String superName = table.immediateSuperClass(classPath).map(JvmInternal::qualified).orElse("java/lang/Object");
        List<String> ifacePaths = table.traitInterfacesJvmOrder(classPath);
        String[] ifaces = ifacePaths.stream().map(JvmInternal::qualified).toArray(String[]::new);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internal, null, superName, ifaces);
        EmitDebug.visitSource(cw, emitCtx.sourceFileName);
        EmitAnnotations.emitForClass(cw, c.attributes(), classCtx, emitCtx.javaInterop);

        for (FieldParam fp : c.ctorFields()) {
            Ty ft = Elaborator.elaborate(fp.type(), classCtx);
            cw.visitField(Opcodes.ACC_PUBLIC, fp.name(), JvmDescriptors.fieldDescriptor(ft), null, null);
        }

        emitInstanceConstructor(cw, c, classPath, internal, table, u, classCtx, emitCtx);

        for (ClassMethod m : c.methods()) {
            emitClassMethod(cw, m, classPath, internal, emitCtx, classCtx);
        }

        cw.visitEnd();
        writeClass(emitCtx.outputDir, internal, cw.toByteArray());
    }

    private static void emitInstanceConstructor(
            ClassWriter cw,
            Item.Class c,
            String classPath,
            String internal,
            TypeTable table,
            Unifier u,
            ElabContext classCtx,
            EmitContext emitCtx) {
        Scheme ctorSch = table.lookupConstructor(classPath).orElseThrow().scheme();
        Ty.FunTy ctorFun = (Ty.FunTy) u.applyFully(ctorSch.instantiate(u));
        String ctorDesc = JvmDescriptors.methodDescriptor(ctorFun.params(), Ty.prim(Prim.UNIT));

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorDesc, null, null);
        mv.visitCode();

        mv.visitVarInsn(Opcodes.ALOAD, 0);

        LocalTable locals = new LocalTable();
        locals.skipReceiver(Ty.nom(classPath, List.of()));
        for (FieldParam fp : c.ctorFields()) {
            Ty ft = Elaborator.elaborate(fp.type(), classCtx);
            locals.bindParam(fp.name(), ft);
        }

        ExprAsm ex = new ExprAsm(mv, internal, emitCtx, classCtx, locals);

        Optional<String> yakouSuper = table.immediateSuperClass(classPath);
        if (yakouSuper.isPresent()) {
            var superCall = c.superRef().orElseThrow();
            for (Expr arg : superCall.args()) {
                ex.emitExpr(arg);
            }
            Optional<TypeTable.ConstructorSpec> yakouSuperCtor = table.lookupConstructor(yakouSuper.get());
            if (yakouSuperCtor.isPresent()) {
                Scheme superCtorSch = yakouSuperCtor.get().scheme();
                Ty.FunTy sfun = (Ty.FunTy) u.applyFully(superCtorSch.instantiate(u));
                String superDesc = JvmDescriptors.methodDescriptor(sfun.params(), Ty.prim(Prim.UNIT));
                mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        JvmInternal.qualified(yakouSuper.get()),
                        "<init>",
                        superDesc,
                        false);
            } else {
                Class<?> superCls = JavaEmit.loadYkNominal(emitCtx.javaInterop, yakouSuper.get());
                java.lang.reflect.Constructor<?> ctor = emitCtx.javaInterop
                        .findInstanceConstructor(superCls, superCall.args().size())
                        .orElseThrow(
                                () -> new CodegenException(
                                        "no JVM superclass <init> for arity "
                                                + superCall.args().size()
                                                + " on "
                                                + yakouSuper.get()));
                String owner = Type.getInternalName(ctor.getDeclaringClass());
                mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        owner,
                        "<init>",
                        Type.getConstructorDescriptor(ctor),
                        false);
            }
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        }

        int slot = 1;
        for (FieldParam fp : c.ctorFields()) {
            Ty ft = Elaborator.elaborate(fp.type(), classCtx);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            TyStack sk = TyStack.of(ft);
            mv.visitVarInsn(sk.loadOpcode(), slot);
            mv.visitFieldInsn(Opcodes.PUTFIELD, internal, fp.name(), JvmDescriptors.fieldDescriptor(ft));
            slot += sk.localSlots();
        }

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitClassMethod(
            ClassWriter cw,
            ClassMethod m,
            String classPath,
            String ownerInternal,
            EmitContext emitCtx,
            ElabContext classCtx) {
        Scheme raw = emitCtx.table.lookupClassMethod(classPath, m.name()).orElseThrow();
        Ty funTy = emitCtx.unifier.applyFully(raw.instantiate(emitCtx.unifier));
        if (!(funTy instanceof Ty.FunTy ft)) {
            throw new CodegenException("expected function type for " + classPath + "::" + m.name());
        }

        boolean hasExplicitSelf = !m.params().isEmpty()
                && "self".equals(m.params().getFirst().name())
                && m.params().getFirst().type().isEmpty();

        /*
         * JVM mapping: every class method is an instance method by default (implicit
         * {@code this}), matching Java / Bukkit APIs like {@code onCommand(...)}
         * without a {@code self} parameter. {@code self} as the first Yakou parameter
         * names slot 0 and is omitted from the JVM descriptor.
         */
        boolean isJvmInstance = true;

        List<Ty> jvmParams = hasExplicitSelf ? ft.params().subList(1, ft.params().size()) : ft.params();
        String desc = JvmDescriptors.methodDescriptor(jvmParams, ft.ret());

        int acc = Opcodes.ACC_PUBLIC;
        if (!isJvmInstance) {
            acc |= Opcodes.ACC_STATIC;
        }

        MethodVisitor mv = cw.visitMethod(acc, m.name(), desc, null, null);
        EmitAnnotations.emitForMethod(mv, m.attributes(), classCtx, emitCtx.javaInterop);

        mv.visitCode();

        LocalTable locals = new LocalTable();
        ElabContext methodCtx = classCtx;
        if (isJvmInstance && !hasExplicitSelf) {
            locals.skipReceiver(Ty.nom(classPath, List.of()));
        }
        for (Param pa : m.params()) {
            Ty pt;
            if (pa.type().isEmpty() && "self".equals(pa.name())) {
                pt = Ty.nom(classPath, List.of());
            } else if (pa.type().isPresent()) {
                pt = Elaborator.elaborate(pa.type().get(), methodCtx);
            } else {
                throw new CodegenException("parameter needs type or self: " + pa.name());
            }
            locals.bindParam(pa.name(), pt);
        }

        ExprAsm ex = new ExprAsm(mv, ownerInternal, emitCtx, methodCtx, locals);
        FnBody body = m.body();
        switch (body) {
            case FnBody.BlockBody bb -> {
                BlockAsm.emitBlock(ex, bb.block(), locals);
                Block b = bb.block();
                if (b.trailingExpr().isPresent()) {
                    Ty t = ex.requireType(b.trailingExpr().get());
                    TyStack.emitReturn(mv, t);
                } else {
                    mv.visitInsn(Opcodes.RETURN);
                }
            }
            case FnBody.ExprEquals ee -> {
                ex.emitExpr(ee.expr());
                TyStack.emitReturn(mv, ex.requireType(ee.expr()));
            }
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitModuleFn(ClassWriter cw, FnEntry fe, EmitContext emitCtx) {
        Scheme sch = emitCtx.table.lookupValueQualified(fe.qualified).orElseThrow();
        Ty funTy = emitCtx.unifier.applyFully(sch.instantiate(emitCtx.unifier));
        if (!(funTy instanceof Ty.FunTy ft)) {
            throw new CodegenException("expected function type for " + fe.qualified);
        }
        String desc = JvmDescriptors.methodDescriptor(ft.params(), ft.ret());

        String methodName = JvmClassNames.mangleMethodName(fe.qualified);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, desc, null, null);

        ElabContext ctx = ElabContext.root(emitCtx.unifier, emitCtx.table);
        int li = fe.qualified.lastIndexOf("::");
        String pkg = li < 0 ? "" : fe.qualified.substring(0, li);
        if (!pkg.isEmpty()) {
            ctx = ctx.withPackage(pkg);
        }
        LocalTable locals = new LocalTable();
        for (int i = 0; i < fe.fn().params().size(); i++) {
            Ty pt = Elaborator.elaborate(fe.fn().params().get(i).type().orElseThrow(), ctx);
            locals.bindParam(fe.fn().params().get(i).name(), pt);
        }

        EmitAnnotations.emitForMethod(mv, fe.fn().attributes(), ctx, emitCtx.javaInterop);
        mv.visitCode();

        ExprAsm ex = new ExprAsm(mv, emitCtx.moduleInternalName, emitCtx, ctx, locals);
        FnBody body = fe.fn().body();
        switch (body) {
            case FnBody.BlockBody bb -> {
                BlockAsm.emitBlock(ex, bb.block(), locals);
                Block b = bb.block();
                if (b.trailingExpr().isPresent()) {
                    Ty t = ex.requireType(b.trailingExpr().get());
                    TyStack.emitReturn(mv, t);
                } else {
                    mv.visitInsn(Opcodes.RETURN);
                }
            }
            case FnBody.ExprEquals ee -> {
                ex.emitExpr(ee.expr());
                TyStack.emitReturn(mv, ex.requireType(ee.expr()));
            }
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitDefaultConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitJvmMain(ClassWriter cw, String internal) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V",
                null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, internal, "yk_main", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static Map<String, Ty> freshTypeParams(List<TypeParam> tps, Unifier u) {
        Map<String, Ty> m = new HashMap<>();
        for (TypeParam tp : tps) {
            m.put(tp.name(), Ty.var(u.fresh()));
        }
        return m;
    }

    private static void collectClasses(
            List<Item> items, String prefix, Set<String> pathsOut, Map<String, Item.Class> astOut) {
        for (Item it : items) {
            switch (it) {
                case Item.Class c -> {
                    String p = qualify(prefix, c.name());
                    pathsOut.add(p);
                    astOut.put(p, c);
                }
                case Item.Pkg p -> collectClasses(p.items(), qualify(prefix, p.name()), pathsOut, astOut);
                default -> {
                }
            }
        }
    }

    private static void collectFns(List<Item> items, String prefix, List<FnEntry> out) {
        for (Item it : items) {
            switch (it) {
                case Item.Fn f -> out.add(new FnEntry(qualify(prefix, f.name()), f));
                case Item.Pkg p -> collectFns(p.items(), qualify(prefix, p.name()), out);
                default -> {
                }
            }
        }
    }

    private static String qualify(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + "::" + name;
    }

    private static void writeClass(Path outputDir, String internalName, byte[] bytes) throws IOException {
        Path rel = Path.of(internalName + ".class");
        Path out = outputDir.resolve(rel);
        Files.createDirectories(out.getParent());
        Files.write(out, bytes);
    }

    /**
     * {@code true} when this file defines the entry {@code fn main} at module root
     * (emitted as
     * {@code yk_main}), not a nested {@code pkg::main}.
     */
    public static boolean moduleDefinesYkMain(AstFile ast) {
        return definesQualifiedFn(ast.items(), "", "main");
    }

    private static boolean definesQualifiedFn(List<Item> items, String prefix, String qualifiedTarget) {
        for (Item it : items) {
            switch (it) {
                case Item.Fn f -> {
                    if (qualifiedTarget.equals(qualify(prefix, f.name()))) {
                        return true;
                    }
                }
                case Item.Pkg p -> {
                    if (definesQualifiedFn(p.items(), qualify(prefix, p.name()), qualifiedTarget)) {
                        return true;
                    }
                }
                default -> {
                }
            }
        }
        return false;
    }
}
