package dev.icxd.yakou.typeck;

import dev.icxd.yakou.ast.Attribute;
import dev.icxd.yakou.ast.AstFile;
import dev.icxd.yakou.ast.BinaryOp;
import dev.icxd.yakou.ast.Block;
import dev.icxd.yakou.ast.ClassMethod;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.ast.FieldParam;
import dev.icxd.yakou.ast.FnBody;
import dev.icxd.yakou.ast.Item;
import dev.icxd.yakou.ast.MethodSig;
import dev.icxd.yakou.ast.Modifier;
import dev.icxd.yakou.ast.Param;
import dev.icxd.yakou.ast.Stmt;
import dev.icxd.yakou.ast.SuperRef;
import dev.icxd.yakou.ast.TypeParam;
import dev.icxd.yakou.ast.TypeRef;
import dev.icxd.yakou.ast.WhenArm;
import dev.icxd.yakou.syntax.SourceSpan;

import java.lang.reflect.Constructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Hindley–Milner style type checking: collect signatures from items, then infer
 * each body with unification and let-generalization.
 */
public final class TypeChecker {

    /**
     * When non-null during {@link #check(AstFile, List, Map)}, inferred types are
     * stored for each {@link Expr} (for codegen).
     */
    private static final ThreadLocal<Map<Expr, Ty>> EXPR_TYPES = new ThreadLocal<>();

    private TypeChecker() {
    }

    public static TypeResult check(AstFile file) {
        return check(file, List.of());
    }

    /**
     * @param classpath compiler {@code -cp} entries; used to load JVM classes for
     *                  Java interop
     */
    public static TypeResult check(AstFile file, List<Path> classpath) {
        return check(file, classpath, null);
    }

    /**
     * @param exprTypesOut if non-null, receives {@code applyFully}-resolved types
     *                     for every expression (use {@link IdentityHashMap}).
     */
    public static TypeResult check(AstFile file, List<Path> classpath, Map<Expr, Ty> exprTypesOut) {
        return checkBundle(file, classpath, exprTypesOut).result();
    }

    /**
     * Like {@link #check(AstFile, List, Map)} but returns {@link TypeTable} and
     * {@link Unifier} for codegen.
     */
    public static TypecheckBundle checkBundle(AstFile file, List<Path> classpath, Map<Expr, Ty> exprTypesOut) {
        EXPR_TYPES.set(exprTypesOut);
        try {
            Unifier u = new Unifier();
            TypeTable table = new TypeTable();
            TypeResult res = new TypeResult();
            JavaInterop java = new JavaInterop(classpath);
            registerNominals(file.items(), "", table);
            ElabContext root = ElabContext.root(u, table);
            registerAllTraits(file.items(), "", root, table, u, res);
            if (!res.ok()) {
                return new TypecheckBundle(res, table, u);
            }
            populateSignaturesAfterTraits(file.items(), "", root, table, u, java, res);
            if (!res.ok()) {
                return new TypecheckBundle(res, table, u);
            }
            table.finalizeSealedHierarchy();
            checkBodies(file.items(), "", root, table, u, java, res);
            return new TypecheckBundle(res, table, u);
        } finally {
            EXPR_TYPES.remove();
        }
    }

    private static void recordExprType(Expr e, Unifier u, Ty ty) {
        Map<Expr, Ty> m = EXPR_TYPES.get();
        if (m != null) {
            m.put(e, u.applyFully(ty));
        }
    }

    private static String qualify(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + "::" + name;
    }

    private static void registerNominals(List<Item> items, String prefix, TypeTable table) {
        for (Item it : items) {
            switch (it) {
                case Item.Use ignored -> {
                }
                case Item.Trait t -> table.registerNominal(qualify(prefix, t.name()));
                case Item.Class c -> table.registerNominal(qualify(prefix, c.name()));
                case Item.Pkg p -> registerNominals(p.items(), qualify(prefix, p.name()), table);
                case Item.Fn ignored -> {
                }
            }
        }
    }

    /**
     * Register every trait in the file (including nested packages) before any class
     * bodies.
     */
    private static void registerAllTraits(
            List<Item> items,
            String prefix,
            ElabContext root,
            TypeTable table,
            Unifier u,
            TypeResult res) {
        for (Item it : items) {
            switch (it) {
                case Item.Pkg p ->
                    registerAllTraits(p.items(), qualify(prefix, p.name()), root, table, u, res);
                case Item.Trait t -> registerTrait(t, prefix, root, table, u);
                case Item.Use use -> {
                }
                case Item.Class c -> {
                }
                case Item.Fn f -> {
                }
            }
        }
    }

    private static void populateSignaturesAfterTraits(
            List<Item> items,
            String prefix,
            ElabContext root,
            TypeTable table,
            Unifier u,
            JavaInterop java,
            TypeResult res) {
        for (Item it : items) {
            switch (it) {
                case Item.Use useStmt -> table.registerJavaImport(useStmt.path());
                case Item.Trait ignored -> {
                }
                case Item.Class c -> registerClass(c, prefix, root, table, u, java, res);
                case Item.Pkg p ->
                    populateSignaturesAfterTraits(
                            p.items(), qualify(prefix, p.name()), root, table, u, java, res);
                case Item.Fn f -> registerFn(f, prefix, root, table, u, res);
            }
        }
    }

    private static Map<String, Ty> freshTypeParams(List<TypeParam> tps, Unifier u) {
        Map<String, Ty> m = new HashMap<>();
        for (TypeParam tp : tps) {
            m.put(tp.name(), Ty.var(u.fresh()));
        }
        return m;
    }

    private static void registerTrait(Item.Trait t, String prefix, ElabContext root, TypeTable table, Unifier u) {
        String traitPath = qualify(prefix, t.name());
        table.markTrait(traitPath);
        if (t.modifiers().contains(Modifier.SEALED)) {
            table.markTraitSealed(traitPath);
        }
        Map<String, Ty> tpMap = freshTypeParams(t.typeParams(), u);
        ElabContext ctx = root.withPackage(prefix).withTypeParams(tpMap);
        List<Ty> targs = new ArrayList<>();
        for (TypeParam tp : t.typeParams()) {
            targs.add(tpMap.get(tp.name()));
        }
        Set<TyVar> quant = new HashSet<>();
        for (Ty tv : tpMap.values()) {
            quant.add(((Ty.VarTy) tv).var());
        }
        for (MethodSig ms : t.methods()) {
            Ty selfTy = Ty.nom(traitPath, targs);
            List<Ty> pty = new ArrayList<>();
            for (Param pa : ms.params()) {
                if (pa.type().isEmpty()) {
                    pty.add(selfTy);
                } else {
                    pty.add(Elaborator.elaborate(pa.type().get(), ctx));
                }
            }
            Ty ret = Elaborator.elaborate(ms.returnType(), ctx);
            Scheme sch = new Scheme(quant, Ty.fun(pty, ret));
            table.registerValue(traitPath + "::" + ms.name(), sch);
            table.registerClassMethod(traitPath, ms.name(), sch);
        }
    }

    private static void registerClass(
            Item.Class c,
            String prefix,
            ElabContext root,
            TypeTable table,
            Unifier u,
            JavaInterop java,
            TypeResult res) {
        String classPath = qualify(prefix, c.name());
        Map<String, Ty> tpMap = freshTypeParams(c.typeParams(), u);
        ElabContext ctx = root.withPackage(prefix).withTypeParams(tpMap);
        List<Ty> classArgs = new ArrayList<>();
        for (TypeParam tp : c.typeParams()) {
            classArgs.add(tpMap.get(tp.name()));
        }
        ElabContext classCtx = ctx.withSelf(c.name(), classPath);
        List<Ty> fieldTys = new ArrayList<>();
        for (FieldParam fp : c.ctorFields()) {
            Ty ft = Elaborator.elaborate(fp.type(), classCtx);
            fieldTys.add(ft);
            table.registerClassField(classPath, fp.name(), ft);
        }
        Set<TyVar> quant = new HashSet<>();
        for (Ty tv : tpMap.values()) {
            quant.add(((Ty.VarTy) tv).var());
        }
        Ty retNominal = Ty.nom(classPath, classArgs);
        Scheme ctorScheme = new Scheme(quant, Ty.fun(fieldTys, retNominal));
        List<TyVar> ctorParamOrder = c.typeParams().stream().map(tp -> ((Ty.VarTy) tpMap.get(tp.name())).var())
                .toList();
        table.registerConstructor(classPath, ctorScheme, ctorParamOrder);
        c.superRef()
                .ifPresent(
                        sr -> {
                            Ty superTy = Elaborator.elaborate(sr.type(), classCtx);
                            if (!(superTy instanceof Ty.NomTy superNom)) {
                                return;
                            }
                            String superPath = superNom.path();
                            Ty classNom = Ty.nom(classPath, classArgs);
                            if (table.isTrait(superPath)) {
                                checkTraitMethodImpls(c, superPath, table, res);
                                table.registerImplementsTrait(classPath, superPath);
                            } else {
                                boolean yakouCtor = table.lookupConstructor(superPath).isPresent();
                                boolean jvmSuper = java.hasLoadableClass(superPath);
                                if (yakouCtor) {
                                    table.registerImmediateSuperClass(classPath, superPath);
                                    table.inheritTraitMethods(
                                            superPath, classPath, sch -> substTraitSelf(sch, superTy, classNom));
                                } else if (jvmSuper) {
                                    java.ensureClasspathNominal(table, superPath);
                                    Optional<Class<?>> loaded = java
                                            .loadClassFromInternal(JavaInterop.ykPathToInternal(superPath));
                                    if (loaded.isPresent() && loaded.get().isInterface()) {
                                        table.registerJvmInterface(classPath, superPath);
                                    } else {
                                        table.registerImmediateSuperClass(classPath, superPath);
                                    }
                                } else {
                                    res.add(
                                            TypeIssue.of(
                                                    "unknown superclass (not a Yakou class or loadable JVM type): "
                                                            + superPath,
                                                    c.superRef().flatMap(SuperRef::span)));
                                }
                            }
                        });
        for (ClassMethod m : c.methods()) {
            Scheme sch = methodScheme(m, classPath, classArgs, quant, classCtx, table, u);
            table.registerClassMethod(classPath, m.name(), sch);
            table.registerValue(classPath + "::" + m.name(), sch);
        }
    }

    private static void checkTraitMethodImpls(
            Item.Class c, String traitPath, TypeTable table, TypeResult res) {
        Set<String> required = table.traitMethodNames(traitPath);
        Set<String> provided = new HashSet<>();
        for (ClassMethod m : c.methods()) {
            provided.add(m.name());
        }
        Optional<SourceSpan> span = c.span();
        for (String name : required) {
            if (!provided.contains(name)) {
                res.add(
                        TypeIssue.of(
                                "missing implementation for trait method '" + traitPath + "::" + name + "'",
                                span));
            }
        }
    }

    /**
     * Replace trait {@code self} nominal with implementing class nominal in a trait
     * method scheme.
     */
    private static Scheme substTraitSelf(Scheme sch, Ty traitSelf, Ty classSelf) {
        return new Scheme(sch.quantified(), replaceNominal(sch.body(), traitSelf, classSelf));
    }

    private static Ty replaceNominal(Ty t, Ty from, Ty to) {
        Ty d = Ty.deref(t);
        if (nominalsMatch(d, from)) {
            return to;
        }
        return switch (d) {
            case Ty.PrimTy p -> p;
            case Ty.VarTy v -> v;
            case Ty.FunTy f -> {
                List<Ty> ps = new ArrayList<>();
                for (Ty p : f.params()) {
                    ps.add(replaceNominal(p, from, to));
                }
                yield Ty.fun(ps, replaceNominal(f.ret(), from, to));
            }
            case Ty.NomTy n -> {
                List<Ty> args = new ArrayList<>();
                for (Ty a : n.typeArgs()) {
                    args.add(replaceNominal(a, from, to));
                }
                Ty rebuilt = Ty.nom(n.path(), args);
                yield nominalsMatch(rebuilt, from) ? to : rebuilt;
            }
            case Ty.ArrayTy ar -> Ty.arrayOf(replaceNominal(ar.element(), from, to));
        };
    }

    private static boolean nominalsMatch(Ty a, Ty b) {
        if (!(a instanceof Ty.NomTy na && b instanceof Ty.NomTy nb)) {
            return false;
        }
        if (!na.path().equals(nb.path()) || na.typeArgs().size() != nb.typeArgs().size()) {
            return false;
        }
        for (int i = 0; i < na.typeArgs().size(); i++) {
            if (!Ty.deref(na.typeArgs().get(i)).equals(Ty.deref(nb.typeArgs().get(i)))) {
                return false;
            }
        }
        return true;
    }

    private static Scheme methodScheme(
            ClassMethod m,
            String classPath,
            List<Ty> classArgs,
            Set<TyVar> classQuant,
            ElabContext ctx,
            TypeTable table,
            Unifier u) {
        Ty selfTy = Ty.nom(classPath, classArgs);
        List<Ty> pty = new ArrayList<>();
        for (Param pa : m.params()) {
            if (pa.type().isEmpty() && "self".equals(pa.name())) {
                pty.add(selfTy);
            } else if (pa.type().isPresent()) {
                pty.add(Elaborator.elaborate(pa.type().get(), ctx));
            } else {
                throw new IllegalStateException("method parameter '" + pa.name() + "' needs a type or 'self'");
            }
        }
        Ty ret = Elaborator.elaborate(m.returnType(), ctx);
        Ty fun = Ty.fun(pty, ret);
        if (classQuant.isEmpty()) {
            return Scheme.mono(fun);
        }
        return new Scheme(classQuant, fun);
    }

    private static void registerFn(
            Item.Fn f, String prefix, ElabContext root, TypeTable table, Unifier u, TypeResult res) {
        ElabContext ctx = root.withPackage(prefix);
        List<Ty> params = new ArrayList<>();
        for (Param p : f.params()) {
            if (p.type().isEmpty()) {
                res.add(
                        TypeIssue.of(
                                "parameter '" + p.name() + "' in fn '" + f.name() + "' needs a type",
                                p.span()));
                return;
            }
            params.add(Elaborator.elaborate(p.type().get(), ctx));
        }
        Ty ret = Elaborator.elaborate(f.returnType(), ctx);
        table.registerValue(qualify(prefix, f.name()), Scheme.mono(Ty.fun(params, ret)));
    }

    private static void checkBodies(
            List<Item> items,
            String prefix,
            ElabContext root,
            TypeTable table,
            Unifier u,
            JavaInterop java,
            TypeResult res) {
        for (Item it : items) {
            switch (it) {
                case Item.Use ignored -> {
                }
                case Item.Trait t -> checkTraitAttributes(t, prefix, root, table, u, java, res);
                case Item.Class c -> {
                    checkClassMethods(c, prefix, root, table, u, java, res);
                    checkSuperConstructorArgs(c, prefix, root, table, u, java, res);
                }
                case Item.Pkg p -> {
                    ElabContext pkgCtx = root.withPackage(prefix);
                    for (Attribute a : p.attributes()) {
                        inferExpr(a.invocation(), new Env(), pkgCtx, table, u, java, res);
                    }
                    checkBodies(p.items(), qualify(prefix, p.name()), root, table, u, java, res);
                }
                case Item.Fn f -> checkFn(f, prefix, root, table, u, java, res);
            }
        }
    }

    private static void checkTraitAttributes(
            Item.Trait t,
            String prefix,
            ElabContext root,
            TypeTable table,
            Unifier u,
            JavaInterop java,
            TypeResult res) {
        Map<String, Ty> tpMap = freshTypeParams(t.typeParams(), u);
        ElabContext ctx = root.withPackage(prefix).withTypeParams(tpMap);
        Env env = new Env();
        for (Attribute a : t.attributes()) {
            inferExpr(a.invocation(), env, ctx, table, u, java, res);
        }
    }

    /**
     * Type-check superclass constructor call arguments
     * ({@code class Dog(...): Animal(name);}) so
     * codegen can rely on {@code exprTypes} for those expressions.
     */
    private static void checkSuperConstructorArgs(
            Item.Class c,
            String prefix,
            ElabContext root,
            TypeTable table,
            Unifier u,
            JavaInterop java,
            TypeResult res) {
        String classPath = qualify(prefix, c.name());
        Optional<String> yakouSuper = table.immediateSuperClass(classPath);
        if (yakouSuper.isEmpty() || c.superRef().isEmpty()) {
            return;
        }
        Map<String, Ty> tpMap = freshTypeParams(c.typeParams(), u);
        ElabContext ctx = root.withPackage(prefix).withTypeParams(tpMap).withSelf(c.name(), classPath);
        Env env = new Env();
        for (FieldParam fp : c.ctorFields()) {
            Ty ft = Elaborator.elaborate(fp.type(), ctx);
            env.put(fp.name(), Scheme.mono(ft));
        }
        TypeTable.ConstructorSpec superCtor = table.lookupConstructor(yakouSuper.get()).orElse(null);
        List<Expr> args = c.superRef().get().args();
        if (superCtor != null) {
            Ty ctorTy = superCtor.scheme().instantiate(u);
            if (!(ctorTy instanceof Ty.FunTy sfun)) {
                return;
            }
            if (sfun.params().size() != args.size()) {
                res.add(
                        TypeIssue.of(
                                "super constructor expects "
                                        + sfun.params().size()
                                        + " argument(s), got "
                                        + args.size(),
                                c.superRef().get().span()));
                return;
            }
            for (int i = 0; i < args.size(); i++) {
                Ty got = inferExpr(args.get(i), env, ctx, table, u, java, res);
                if (!u.unify(got, sfun.params().get(i))) {
                    res.add(
                            TypeIssue.of(
                                    "super constructor argument "
                                            + (i + 1)
                                            + " type mismatch (expected "
                                            + u.applyFully(sfun.params().get(i))
                                            + ", got "
                                            + u.applyFully(got)
                                            + ")",
                                    args.get(i).span()));
                }
            }
            return;
        }
        if (!java.hasLoadableClass(yakouSuper.get())) {
            return;
        }
        Optional<Class<?>> superClazz = java.loadClassFromInternal(JavaInterop.ykPathToInternal(yakouSuper.get()));
        if (superClazz.isEmpty()) {
            return;
        }
        Optional<Constructor<?>> jCtor = java.findInstanceConstructor(superClazz.get(), args.size());
        if (jCtor.isEmpty()) {
            res.add(
                    TypeIssue.of(
                            "no accessible JVM superclass constructor with "
                                    + args.size()
                                    + " parameter(s) on "
                                    + yakouSuper.get(),
                            c.superRef().get().span()));
            return;
        }
        Class<?>[] paramTypes = jCtor.get().getParameterTypes();
        for (int i = 0; i < args.size(); i++) {
            Ty expected = java.reflectTypeToTy(paramTypes[i], table);
            Ty got = inferExpr(args.get(i), env, ctx, table, u, java, res);
            if (!u.unify(got, expected)) {
                res.add(
                        TypeIssue.of(
                                "super constructor argument "
                                        + (i + 1)
                                        + " type mismatch (expected "
                                        + u.applyFully(expected)
                                        + ", got "
                                        + u.applyFully(got)
                                        + ")",
                                args.get(i).span()));
            }
        }
    }

    private static void checkClassMethods(
            Item.Class c,
            String prefix,
            ElabContext root,
            TypeTable table,
            Unifier u,
            JavaInterop java,
            TypeResult res) {
        String classPath = qualify(prefix, c.name());
        Map<String, Ty> tpMap = freshTypeParams(c.typeParams(), u);
        ElabContext ctxHead = root.withPackage(prefix).withTypeParams(tpMap);
        for (Attribute a : c.attributes()) {
            inferExpr(a.invocation(), new Env(), ctxHead, table, u, java, res);
        }
        ElabContext ctx = ctxHead.withSelf(c.name(), classPath);
        List<Ty> classArgs = new ArrayList<>();
        for (TypeParam tp : c.typeParams()) {
            classArgs.add(tpMap.get(tp.name()));
        }
        Ty selfTy = Ty.nom(classPath, classArgs);
        for (ClassMethod m : c.methods()) {
            for (Attribute a : m.attributes()) {
                inferExpr(a.invocation(), new Env(), ctx, table, u, java, res);
            }
            Env env = new Env();
            for (Param pa : m.params()) {
                if (pa.type().isEmpty() && "self".equals(pa.name())) {
                    env.put("self", Scheme.mono(selfTy));
                } else if (pa.type().isPresent()) {
                    Ty pt = Elaborator.elaborate(pa.type().get(), ctx);
                    env.put(pa.name(), Scheme.mono(pt));
                } else {
                    res.add(TypeIssue.of("missing type for parameter '" + pa.name() + "'", pa.span()));
                    return;
                }
            }
            Ty expectRet = Elaborator.elaborate(m.returnType(), ctx);
            checkFnBody(m.body(), env, expectRet, ctx, table, u, java, res);
        }
    }

    private static void checkFn(
            Item.Fn f,
            String prefix,
            ElabContext root,
            TypeTable table,
            Unifier u,
            JavaInterop java,
            TypeResult res) {
        ElabContext ctx = root.withPackage(prefix);
        for (Attribute a : f.attributes()) {
            inferExpr(a.invocation(), new Env(), ctx, table, u, java, res);
        }
        Env env = new Env();
        for (Param p : f.params()) {
            if (p.type().isEmpty()) {
                res.add(TypeIssue.of("missing type for parameter '" + p.name() + "'", p.span()));
                return;
            }
            env.put(p.name(), Scheme.mono(Elaborator.elaborate(p.type().get(), ctx)));
        }
        Ty expectRet = Elaborator.elaborate(f.returnType(), ctx);
        checkFnBody(f.body(), env, expectRet, ctx, table, u, java, res);
    }

    private static void checkFnBody(
            FnBody body,
            Env env,
            Ty expectRet,
            ElabContext ctx,
            TypeTable table,
            Unifier u,
            JavaInterop java,
            TypeResult res) {
        switch (body) {
            case FnBody.BlockBody bb -> {
                Ty got = inferBlock(bb.block(), env, ctx, table, u, java, res);
                if (!u.unify(got, expectRet) && !tryTraitSubtypeUnify(u, table, java, got, expectRet)) {
                    res.add(
                            TypeIssue.of(
                                    "return type mismatch: expected " + u.applyFully(expectRet) + " but got "
                                            + u.applyFully(got),
                                    bb.block().span()));
                }
            }
            case FnBody.ExprEquals ee -> {
                Ty got = inferExpr(ee.expr(), env, ctx, table, u, java, res);
                if (!u.unify(got, expectRet) && !tryTraitSubtypeUnify(u, table, java, got, expectRet)) {
                    res.add(
                            TypeIssue.of(
                                    "return type mismatch: expected " + u.applyFully(expectRet) + " but got "
                                            + u.applyFully(got),
                                    ee.span()));
                }
            }
        }
    }

    private static Ty inferBlock(
            Block block, Env env, ElabContext ctx, TypeTable table, Unifier u, JavaInterop java, TypeResult res) {
        Env local = env.extend();
        for (Stmt st : block.statements()) {
            switch (st) {
                case Stmt.Let l -> {
                    Ty vt = inferExpr(l.value(), local, ctx, table, u, java, res);
                    if (l.annotation().isPresent()) {
                        Ty at = Elaborator.elaborate(l.annotation().get(), ctx);
                        if (!u.unify(vt, at) && !tryTraitSubtypeUnify(u, table, java, vt, at)) {
                            res.add(TypeIssue.of("let annotation mismatch", l.span()));
                        } else {
                            vt = u.applyFully(at);
                        }
                    }
                    Scheme sch = Scheme.generalize(vt, local, u);
                    local.put(l.name(), sch);
                }
                case Stmt.ExprSemi es -> inferExpr(es.expr(), local, ctx, table, u, java, res);
            }
        }
        if (block.trailingExpr().isPresent()) {
            return inferExpr(block.trailingExpr().get(), local, ctx, table, u, java, res);
        }
        return Ty.prim(Prim.UNIT);
    }

    private static Ty inferExpr(
            Expr e, Env env, ElabContext ctx, TypeTable table, Unifier u, JavaInterop java, TypeResult res) {
        Ty ty = switch (e) {
            case Expr.Literal lit -> inferLiteral(lit);
            case Expr.Path p -> inferPath(p, env, ctx, table, u, java, res);
            case Expr.Binary b -> inferBinary(b, env, ctx, table, u, java, res);
            case Expr.Member m -> inferMember(m, env, ctx, table, u, java, res);
            case Expr.Call c -> inferCall(c, env, ctx, table, u, java, res);
            case Expr.TypeApply ta -> inferTypeApplyExpr(ta, env, ctx, table, u, java, res);
            case Expr.Index ix -> inferIndexExpr(ix, env, ctx, table, u, java, res);
            case Expr.If i -> inferIf(i, env, ctx, table, u, java, res);
            case Expr.When w -> inferWhen(w, env, ctx, table, u, java, res);
            case Expr.BlockExpr be -> inferBlock(be.block(), env, ctx, table, u, java, res);
        };
        recordExprType(e, u, ty);
        return ty;
    }

    private static Ty inferLiteral(Expr.Literal lit) {
        return switch (lit.kind()) {
            case INT -> Ty.prim(Prim.I32);
            case STRING -> Ty.prim(Prim.STR);
            case BOOL -> Ty.prim(Prim.BOOL);
        };
    }

    private static Ty inferPath(
            Expr.Path p,
            Env env,
            ElabContext ctx,
            TypeTable table,
            Unifier u,
            JavaInterop java,
            TypeResult res) {
        if (p.segments().size() == 1) {
            String name = p.segments().getFirst();
            Optional<Scheme> local = env.get(name);
            if (local.isPresent()) {
                return local.get().instantiate(u);
            }
        }
        Optional<Scheme> sch = table.lookupValue(p.segments());
        if (sch.isEmpty() && p.segments().size() == 1) {
            sch = table.lookupValueQualified(ctx.qualifyNominal(p.segments().getFirst()));
        }
        if (sch.isEmpty() && java != null) {
            Optional<Ty> jt = java.resolveImportedStaticPath(table, p.segments());
            if (jt.isPresent()) {
                return jt.get();
            }
            String qualClass = p.segments().size() == 1
                    ? ctx.qualifyNominal(p.segments().getFirst())
                    : String.join("::", p.segments());
            if (java.hasLoadableClass(qualClass)) {
                Optional<java.lang.Class<?>> oc = java.loadClassFromInternal(JavaInterop.ykPathToInternal(qualClass));
                if (oc.isPresent()) {
                    return java.reflectTypeToTy(oc.get(), table);
                }
            }
        }
        if (sch.isEmpty()) {
            res.add(TypeIssue.of("unknown value in type context: '" + String.join("::", p.segments()) + "'", p.span()));
            return Ty.var(u.fresh());
        }
        return sch.get().instantiate(u);
    }

    private static Ty inferIndexExpr(
            Expr.Index ix,
            Env env,
            ElabContext ctx,
            TypeTable table,
            Unifier u,
            JavaInterop java,
            TypeResult res) {
        Ty recv = inferExpr(ix.target(), env, ctx, table, u, java, res);
        Ty idx = inferExpr(ix.index(), env, ctx, table, u, java, res);
        Ty r = u.applyFully(recv);
        if (!(r instanceof Ty.ArrayTy ar)) {
            res.add(TypeIssue.of("indexing requires an array type, got " + r, ix.span()));
            return Ty.var(u.fresh());
        }
        Ty id = u.applyFully(idx);
        if (id instanceof Ty.PrimTy ipp && PrimSets.isIntegral(ipp.prim())) {
            // ok
        } else if (!(id instanceof Ty.VarTy)) {
            res.add(TypeIssue.of("array index must be an integral type", ix.index().span()));
        }
        return ar.element();
    }

    private static Ty inferTypeApplyExpr(
            Expr.TypeApply ta,
            Env env,
            ElabContext ctx,
            TypeTable table,
            Unifier u,
            JavaInterop java,
            TypeResult res) {
        if (!(ta.base() instanceof Expr.Path path)) {
            res.add(TypeIssue.of("type arguments require a constructor path", ta.span()));
            return Ty.var(u.fresh());
        }
        String qual = qualifyExprPath(path, ctx);
        Optional<TypeTable.ConstructorSpec> spec = table.lookupConstructor(qual);
        if (spec.isEmpty()) {
            res.add(TypeIssue.of("no constructor for '" + qual + "'", ta.span()));
            return Ty.var(u.fresh());
        }
        TypeTable.ConstructorSpec cs = spec.get();
        List<Ty> targs = new ArrayList<>();
        for (TypeRef tr : ta.typeArgs()) {
            targs.add(Elaborator.elaborate(tr, ctx));
        }
        if (cs.typeParametersInOrder().isEmpty()) {
            if (!targs.isEmpty()) {
                res.add(TypeIssue.of("type arguments given for non-generic class", ta.span()));
            }
            return instantiateConstructorTy(cs, List.of());
        }
        if (cs.typeParametersInOrder().size() != targs.size()) {
            res.add(
                    TypeIssue.of(
                            "wrong number of type arguments (expected "
                                    + cs.typeParametersInOrder().size()
                                    + ", got "
                                    + targs.size()
                                    + ")",
                            ta.span()));
            return Ty.var(u.fresh());
        }
        return instantiateConstructorTy(cs, targs);
    }

    private static String qualifyExprPath(Expr.Path p, ElabContext ctx) {
        if (p.segments().size() == 1) {
            return ctx.qualifyNominal(p.segments().getFirst());
        }
        return String.join("::", p.segments());
    }

    /**
     * Whether the expected JVM nominal (e.g. {@code org::bukkit::plugin::Plugin})
     * is assignable from the concrete nominal, using {@link Class#isAssignableFrom}
     * when both load, a direct {@code :} JVM interface, and the recorded Yakou
     * {@link TypeTable#immediateSuperClass} chain (e.g. a plugin class to
     * {@code JavaPlugin} on the classpath, then {@code Plugin} assignable from
     * {@code JavaPlugin}) so arguments typecheck before the Yakou subclass exists
     * as
     * a loaded class file.
     */
    private static boolean jvmNominalAssignable(
            JavaInterop java, TypeTable table, String expectedPath, String concretePath) {
        if (java.classpathAssignable(expectedPath, concretePath)) {
            return true;
        }
        if (table.implementsJvmInterface(concretePath, expectedPath)) {
            return true;
        }
        Optional<String> sup = table.immediateSuperClass(concretePath);
        while (sup.isPresent()) {
            String p = sup.get();
            if (expectedPath.equals(p)) {
                return true;
            }
            if (java.classpathAssignable(expectedPath, p)) {
                return true;
            }
            if (table.implementsJvmInterface(p, expectedPath)) {
                return true;
            }
            sup = table.immediateSuperClass(p);
        }
        return false;
    }

    /**
     * If {@code got} is a class type that {@link TypeTable} records as implementing
     * the trait {@code expected}, unify type arguments positionally (same arity).
     */
    private static boolean tryTraitSubtypeUnify(
            Unifier u, TypeTable table, JavaInterop java, Ty got, Ty expected) {
        Ty g = u.applyFully(got);
        Ty e = u.applyFully(expected);
        if (!(g instanceof Ty.NomTy gn) || !(e instanceof Ty.NomTy en)) {
            return false;
        }
        if (gn.path().equals(en.path())) {
            return false;
        }
        if (table.implementsTrait(gn.path(), en.path())) {
            if (gn.typeArgs().size() != en.typeArgs().size()) {
                return false;
            }
            for (int i = 0; i < gn.typeArgs().size(); i++) {
                if (!u.unify(gn.typeArgs().get(i), en.typeArgs().get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (gn.typeArgs().isEmpty() && en.typeArgs().isEmpty()) {
            if (java != null && jvmNominalAssignable(java, table, en.path(), gn.path())) {
                return true;
            }
        }
        return false;
    }

    private static Ty instantiateConstructorTy(TypeTable.ConstructorSpec spec, List<Ty> typeArgs) {
        return TypeTable.instantiateConstructorFunctionType(spec, typeArgs);
    }

    private static Ty inferBinary(
            Expr.Binary b, Env env, ElabContext ctx, TypeTable table, Unifier u, JavaInterop java, TypeResult res) {
        Ty l = inferExpr(b.left(), env, ctx, table, u, java, res);
        Ty r = inferExpr(b.right(), env, ctx, table, u, java, res);
        if (b.op() == BinaryOp.PLUS) {
            if (!u.unify(l, r)) {
                res.add(TypeIssue.of("operand types differ for '+'", b.span()));
            }
            Ty d = u.applyFully(l);
            if (d instanceof Ty.PrimTy pt && (pt.prim() == Prim.STR || PrimSets.isIntegral(pt.prim()))) {
                return u.applyFully(l);
            }
            res.add(TypeIssue.of("invalid type for '+'", b.span()));
            return u.applyFully(l);
        }
        if (b.op() == BinaryOp.MINUS || b.op() == BinaryOp.MUL || b.op() == BinaryOp.DIV) {
            if (!u.unify(l, r)) {
                res.add(TypeIssue.of("operand types differ for arithmetic op", b.span()));
            }
            Ty d = u.applyFully(l);
            if (d instanceof Ty.PrimTy pt && PrimSets.isIntegral(pt.prim())) {
                return u.applyFully(l);
            }
            res.add(TypeIssue.of("invalid type for arithmetic op", b.span()));
            return u.applyFully(l);
        }
        if (b.op() == BinaryOp.EQEQ || b.op() == BinaryOp.NEQ) {
            if (!u.unify(l, r)) {
                res.add(TypeIssue.of("equality operands must match", b.span()));
            }
            return Ty.prim(Prim.BOOL);
        }
        if (!u.unify(l, r)) {
            res.add(TypeIssue.of("comparison operands must match", b.span()));
        }
        return Ty.prim(Prim.BOOL);
    }

    private static Ty inferIf(
            Expr.If i, Env env, ElabContext ctx, TypeTable table, Unifier u, JavaInterop java, TypeResult res) {
        Ty c = inferExpr(i.cond(), env, ctx, table, u, java, res);
        if (!u.unify(c, Ty.prim(Prim.BOOL))) {
            res.add(TypeIssue.of("if condition must be bool", i.cond().span()));
        }
        Ty t = inferBlock(i.thenBlock(), env, ctx, table, u, java, res);
        Ty e = inferBlock(i.elseBlock(), env, ctx, table, u, java, res);
        if (!u.unify(t, e)) {
            res.add(TypeIssue.of("if branches must have the same type", i.span()));
        }
        return u.applyFully(t);
    }

    private static Ty inferWhen(
            Expr.When w, Env env, ElabContext ctx, TypeTable table, Unifier u, JavaInterop java, TypeResult res) {
        Ty scr = inferExpr(w.scrutinee(), env, ctx, table, u, java, res);
        Ty sd = u.applyFully(scr);
        if (!(sd instanceof Ty.NomTy gn)) {
            res.add(TypeIssue.of("when scrutinee must be a nominal type (sealed trait or variant)",
                    w.scrutinee().span()));
            return Ty.prim(Prim.UNIT);
        }
        Optional<String> traitOpt;
        if (table.isSealedTrait(gn.path())) {
            traitOpt = Optional.of(gn.path());
        } else {
            traitOpt = table.sealedTraitForVariantClass(gn.path());
        }
        if (traitOpt.isEmpty()) {
            res.add(
                    TypeIssue.of(
                            "when scrutinee must be a sealed trait type or a class registered as its variant",
                            w.scrutinee().span()));
        }
        String sealedTrait = traitOpt.orElse("");
        // Exhaustive arms when static type is the sealed trait (not a narrower variant
        // class).
        boolean requireExhaustive = traitOpt.isPresent() && table.isSealedTrait(gn.path());

        Set<String> seenVariantNames = new HashSet<>();
        Set<String> coveredVariantPaths = new HashSet<>();

        Ty common = null;
        for (WhenArm arm : w.arms()) {
            if (!seenVariantNames.add(arm.variant())) {
                res.add(TypeIssue.of("duplicate when arm for variant '" + arm.variant() + "'", arm.span()));
                continue;
            }
            Env armEnv = env.extend();
            if (traitOpt.isPresent()) {
                Optional<String> variantClass = resolveWhenArmVariant(table, sealedTrait, arm.variant());
                if (variantClass.isEmpty()) {
                    res.add(
                            TypeIssue.of(
                                    "unknown when variant '"
                                            + arm.variant()
                                            + "' for sealed trait (expected one of: "
                                            + String.join(
                                                    ", ",
                                                    table.sealedVariantClasses(sealedTrait).stream()
                                                            .map(TypeTable::lastPathSegment)
                                                            .toList())
                                            + ")",
                                    arm.span()));
                } else {
                    coveredVariantPaths.add(variantClass.get());
                    Optional<TypeTable.ConstructorSpec> cs = table.lookupConstructor(variantClass.get());
                    if (cs.isEmpty()) {
                        res.add(TypeIssue.of("no constructor for variant '" + variantClass.get() + "'", arm.span()));
                    } else {
                        Ty ctorTy = instantiateConstructorTy(cs.get(), gn.typeArgs());
                        if (ctorTy instanceof Ty.FunTy fn) {
                            List<Ty> fields = fn.params();
                            if (fields.size() != arm.bindNames().size()) {
                                res.add(
                                        TypeIssue.of(
                                                "when arm binding count must match variant constructor (expected "
                                                        + fields.size()
                                                        + ", got "
                                                        + arm.bindNames().size()
                                                        + ")",
                                                arm.span()));
                            } else {
                                for (int i = 0; i < fields.size(); i++) {
                                    armEnv.put(arm.bindNames().get(i), Scheme.mono(fields.get(i)));
                                }
                            }
                        } else {
                            res.add(TypeIssue.of("invalid constructor type for when variant", arm.span()));
                        }
                    }
                }
            }
            Ty bt = inferBlock(arm.body(), armEnv, ctx, table, u, java, res);
            if (common == null) {
                common = bt;
            } else if (!u.unify(common, bt)) {
                res.add(TypeIssue.of("when arms must agree in type", arm.span()));
            }
        }
        if (requireExhaustive) {
            for (String requiredPath : table.sealedVariantClasses(sealedTrait)) {
                if (!coveredVariantPaths.contains(requiredPath)) {
                    res.add(
                            TypeIssue.of(
                                    "non-exhaustive when: missing variant '"
                                            + TypeTable.lastPathSegment(requiredPath)
                                            + "' (sealed trait '"
                                            + sealedTrait
                                            + "')",
                                    w.span()));
                }
            }
        }
        if (common == null) {
            return Ty.prim(Prim.UNIT);
        }
        return u.applyFully(common);
    }

    private static Optional<String> resolveWhenArmVariant(TypeTable table, String sealedTrait, String armVariant) {
        for (String vc : table.sealedVariantClasses(sealedTrait)) {
            if (TypeTable.lastPathSegment(vc).equals(armVariant)) {
                return Optional.of(vc);
            }
        }
        return Optional.empty();
    }

    /**
     * If {@code m} names an instance method, returns its full function type
     * (including
     * {@code self}) after unifying the receiver with the first parameter; empty if
     * not a method (field or unknown).
     */
    private static Optional<Ty.FunTy> tryInstanceMethod(
            Expr.Member m,
            Env env,
            ElabContext ctx,
            TypeTable table,
            Unifier u,
            JavaInterop java,
            Integer jvmExtraArgCount,
            TypeResult res) {
        Ty recv = inferExpr(m.receiver(), env, ctx, table, u, java, res);
        Ty rd = u.applyFully(recv);
        if (rd instanceof Ty.ArrayTy) {
            return Optional.empty();
        }
        if (!(rd instanceof Ty.NomTy nom)) {
            res.add(TypeIssue.of("member access needs nominal receiver", m.span()));
            return Optional.empty();
        }
        Optional<Scheme> msch = table.lookupClassMethod(nom.path(), m.name());
        if (msch.isPresent()) {
            Ty inst = msch.get().instantiate(u);
            Ty fd = u.applyFully(inst);
            if (!(fd instanceof Ty.FunTy fun)) {
                res.add(TypeIssue.of("member is not a method", m.span()));
                return Optional.empty();
            }
            if (fun.params().isEmpty()) {
                res.add(TypeIssue.of("bad method arity", m.span()));
                return Optional.empty();
            }
            if (!u.unify(recv, fun.params().getFirst())) {
                res.add(TypeIssue.of("'self' type mismatch for method", m.span()));
                return Optional.empty();
            }
            return Optional.of(fun);
        }
        // Inherited methods from classpath superclasses (e.g. JavaPlugin#getLogger).
        if (java != null) {
            Optional<String> supPath = table.immediateSuperClass(nom.path());
            while (supPath.isPresent()) {
                if (java.hasLoadableClass(supPath.get())) {
                    int hint = jvmExtraArgCount != null ? jvmExtraArgCount : -1;
                    Optional<Ty.FunTy> jf = java.instanceMethod(supPath.get(), m.name(), hint, table);
                    if (jf.isPresent()) {
                        Ty.FunTy bridged = replaceInheritedSelfParameter(jf.get(), recv);
                        return Optional.of(bridged);
                    }
                }
                supPath = table.immediateSuperClass(supPath.get());
            }
        }
        if (java != null && java.hasLoadableClass(nom.path())) {
            int hint = jvmExtraArgCount != null ? jvmExtraArgCount : -1;
            Optional<Ty.FunTy> jf = java.instanceMethod(nom.path(), m.name(), hint, table);
            if (jf.isPresent()) {
                Ty.FunTy fun = jf.get();
                if (!u.unify(recv, fun.params().getFirst())) {
                    res.add(TypeIssue.of("'self' type mismatch for Java method", m.span()));
                    return Optional.empty();
                }
                return Optional.of(fun);
            }
        }
        return Optional.empty();
    }

    /**
     * First parameter is JVM {@code self}; replace with the actual Yakou receiver
     * type.
     */
    private static Ty.FunTy replaceInheritedSelfParameter(Ty.FunTy jvmFun, Ty receiver) {
        if (jvmFun.params().isEmpty()) {
            return jvmFun;
        }
        List<Ty> ps = new ArrayList<>(jvmFun.params());
        ps.set(0, receiver);
        return new Ty.FunTy(ps, jvmFun.ret());
    }

    private static Ty inferMember(
            Expr.Member m, Env env, ElabContext ctx, TypeTable table, Unifier u, JavaInterop java, TypeResult res) {
        Ty recvHead = inferExpr(m.receiver(), env, ctx, table, u, java, res);
        Ty rdHead = u.applyFully(recvHead);
        if (rdHead instanceof Ty.ArrayTy && "length".equals(m.name())) {
            return Ty.prim(Prim.I32);
        }
        Optional<Ty.FunTy> mfun = tryInstanceMethod(m, env, ctx, table, u, java, null, res);
        if (mfun.isPresent()) {
            Ty.FunTy fun = mfun.get();
            if (fun.params().size() == 1) {
                return fun.ret();
            }
            return Ty.fun(fun.params().subList(1, fun.params().size()), fun.ret());
        }
        Ty recv = inferExpr(m.receiver(), env, ctx, table, u, java, res);
        Ty rd = u.applyFully(recv);
        if (rd instanceof Ty.ArrayTy) {
            res.add(
                    TypeIssue.of(
                            "unknown member '" + m.name() + "' on array type (supported: 'length')",
                            m.span()));
            return Ty.var(u.fresh());
        }
        if (!(rd instanceof Ty.NomTy nom)) {
            return Ty.var(u.fresh());
        }
        Optional<Ty> fld = table.fieldType(nom.path(), m.name());
        if (fld.isPresent()) {
            return fld.get();
        }
        if (java != null && java.hasLoadableClass(nom.path())) {
            Optional<Ty> jf = java.instanceFieldType(nom.path(), m.name(), table);
            if (jf.isPresent()) {
                return jf.get();
            }
        }
        res.add(TypeIssue.of("unknown member '" + m.name() + "' on '" + nom.path() + "'", m.span()));
        return Ty.var(u.fresh());
    }

    private static Ty inferCall(
            Expr.Call c, Env env, ElabContext ctx, TypeTable table, Unifier u, JavaInterop java, TypeResult res) {
        if (c.callee() instanceof Expr.Member mem) {
            Optional<Ty.FunTy> mfun = tryInstanceMethod(mem, env, ctx, table, u, java, c.args().size(), res);
            if (mfun.isEmpty()) {
                res.add(TypeIssue.of("call target is not a callable method", c.callee().span()));
                return Ty.var(u.fresh());
            }
            Ty.FunTy fun = mfun.get();
            List<Ty> expect = fun.params().subList(1, fun.params().size());
            if (expect.size() != c.args().size()) {
                res.add(TypeIssue.of("wrong argument count in call", c.span()));
            }
            int n = Math.min(expect.size(), c.args().size());
            for (int i = 0; i < n; i++) {
                Ty at = inferExpr(c.args().get(i), env, ctx, table, u, java, res);
                if (!u.unify(expect.get(i), at)
                        && !tryTraitSubtypeUnify(u, table, java, at, expect.get(i))) {
                    res.add(TypeIssue.of("argument " + (i + 1) + " type mismatch", c.span()));
                }
            }
            return fun.ret();
        }
        if (c.callee() instanceof Expr.Path p && p.segments().size() == 2) {
            // Strict Java static method call on an imported short name: System::gc(),
            // Integer::parseInt("1")
            String start = p.segments().getFirst();
            Optional<String> internal = table.javaInternalName(start);
            if (internal.isPresent()) {
                String javaPath = JavaInterop.internalNameToYkPath(internal.get());
                String methodName = p.segments().getLast();
                Optional<Ty.FunTy> sm = java.staticMethod(javaPath, methodName, c.args().size(), table);
                if (sm.isPresent()) {
                    Ty.FunTy fun = sm.get();
                    if (fun.params().size() != c.args().size()) {
                        res.add(TypeIssue.of("wrong argument count in java static call", c.span()));
                    }
                    int n = Math.min(fun.params().size(), c.args().size());
                    for (int i = 0; i < n; i++) {
                        Ty at = inferExpr(c.args().get(i), env, ctx, table, u, java, res);
                        if (!u.unify(fun.params().get(i), at)) {
                            res.add(TypeIssue.of("argument " + (i + 1) + " type mismatch", c.span()));
                        }
                    }
                    return fun.ret();
                }
            }
        }
        Ty calleeT = c.callee() instanceof Expr.TypeApply ta
                ? inferTypeApplyExpr(ta, env, ctx, table, u, java, res)
                : inferExpr(c.callee(), env, ctx, table, u, java, res);
        Ty cd = u.applyFully(calleeT);
        if (!(cd instanceof Ty.FunTy fun)) {
            res.add(TypeIssue.of("call target is not a function", c.callee().span()));
            return Ty.var(u.fresh());
        }
        if (fun.params().size() != c.args().size()) {
            res.add(TypeIssue.of("wrong argument count in call", c.span()));
        }
        int n = Math.min(fun.params().size(), c.args().size());
        for (int i = 0; i < n; i++) {
            Ty at = inferExpr(c.args().get(i), env, ctx, table, u, java, res);
            if (!u.unify(fun.params().get(i), at)
                    && !tryTraitSubtypeUnify(u, table, java, at, fun.params().get(i))) {
                res.add(TypeIssue.of("argument " + (i + 1) + " type mismatch", c.span()));
            }
        }
        return fun.ret();
    }
}
