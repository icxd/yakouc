package dev.icxd.yakou.ast;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Indented dump for debugging / {@code --print-ast} — includes expressions and
 * blocks.
 */
public final class AstPrinter {

    private final StringBuilder sb = new StringBuilder();
    private int indent;

    private AstPrinter() {
    }

    public static String print(AstFile file) {
        AstPrinter p = new AstPrinter();
        p.line("file " + file.fileName());
        p.indent++;
        for (Item it : file.items()) {
            p.printItem(it);
        }
        return p.sb.toString();
    }

    private void printAttrs(List<Attribute> attrs) {
        for (Attribute a : attrs) {
            line("#{" + exprStr(a.invocation()) + "}");
        }
    }

    private void printItem(Item it) {
        switch (it) {
            case Item.Use u -> line("use " + String.join("::", u.path()) + ";");
            case Item.Trait t -> {
                printAttrs(t.attributes());
                line((t.modifiers().contains(Modifier.SEALED) ? "sealed " : "")
                        + "trait " + t.name() + typeParams(t.typeParams()) + " {");
                indent++;
                for (MethodSig m : t.methods()) {
                    line("fn " + m.name() + "(" + params(m.params()) + "): " + type(m.returnType()) + ";");
                }
                indent--;
                line("}");
            }
            case Item.Class c -> {
                printAttrs(c.attributes());
                line(modStr(c.modifiers()) + "class " + c.name() + typeParams(c.typeParams()) + "("
                        + c.ctorFields().stream().map(this::fieldParam).collect(Collectors.joining(", "))
                        + ")"
                        + c.superRef().map(s -> ": " + type(s.type()) + superArgs(s)).orElse("")
                        + (c.stub() ? ";" : " {"));
                if (!c.stub()) {
                    indent++;
                    for (ClassMethod m : c.methods()) {
                        printClassMethod(m);
                    }
                    indent--;
                    line("}");
                }
            }
            case Item.Pkg p -> {
                printAttrs(p.attributes());
                line(modStr(p.modifiers()) + "pkg " + p.name() + " {");
                indent++;
                for (Item inner : p.items()) {
                    printItem(inner);
                }
                indent--;
                line("}");
            }
            case Item.Fn f -> printTopLevelFn(f);
        }
    }

    private void printTopLevelFn(Item.Fn f) {
        printAttrs(f.attributes());
        String head = modStr(f.modifiers()) + "fn " + f.name() + "(" + params(f.params()) + "): "
                + type(f.returnType());
        printFnish(head, f.body());
    }

    private void printClassMethod(ClassMethod m) {
        printAttrs(m.attributes());
        String head = modStr(m.modifiers()) + "fn " + m.name() + "(" + params(m.params()) + "): "
                + type(m.returnType());
        printFnish(head, m.body());
    }

    private void printFnish(String header, FnBody body) {
        switch (body) {
            case FnBody.BlockBody bb -> {
                line(header + " {");
                indent++;
                printBlockInner(bb.block());
                indent--;
                line("}");
            }
            case FnBody.ExprEquals ee -> line(header + " = " + exprStr(ee.expr()) + ";");
        }
    }

    private void printBlock(Block b) {
        line("{");
        indent++;
        printBlockInner(b);
        indent--;
        line("}");
    }

    /** Block body without wrapping braces (already inside `{` … `}`). */
    private void printBlockInner(Block b) {
        for (Stmt s : b.statements()) {
            printStmt(s);
        }
        b.trailingExpr().ifPresent(this::printTrailingExpr);
    }

    private void printStmt(Stmt s) {
        switch (s) {
            case Stmt.Let l -> {
                String ann = l.annotation().map(t -> ": " + type(t)).orElse("");
                line("let " + l.name() + ann + " = " + exprStr(l.value()) + ";");
            }
            case Stmt.ExprSemi es -> {
                Expr e = es.expr();
                if (needsMultilineExpr(e)) {
                    printExprMultiline(e);
                    line(";");
                } else {
                    line(exprStr(e) + ";");
                }
            }
        }
    }

    private void printTrailingExpr(Expr e) {
        if (needsMultilineExpr(e)) {
            printExprMultiline(e);
        } else {
            line(exprStr(e));
        }
    }

    private boolean needsMultilineExpr(Expr e) {
        return e instanceof Expr.If || e instanceof Expr.When || e instanceof Expr.BlockExpr;
    }

    private void printExprMultiline(Expr e) {
        switch (e) {
            case Expr.If i -> printIfExpr(i);
            case Expr.When w -> printWhenExpr(w);
            case Expr.BlockExpr be -> printBlock(be.block());
            default -> line(exprStr(e));
        }
    }

    private void printIfExpr(Expr.If i) {
        line("if (" + exprStr(i.cond()) + ") {");
        indent++;
        printBlockInner(i.thenBlock());
        indent--;
        line("} else {");
        indent++;
        printBlockInner(i.elseBlock());
        indent--;
        line("}");
    }

    private void printWhenExpr(Expr.When w) {
        line("when " + exprStr(w.scrutinee()) + " {");
        indent++;
        for (WhenArm a : w.arms()) {
            String binds = a.bindNames().stream().map(v -> "let " + v).collect(Collectors.joining(", "));
            line("is " + a.variant() + "(" + binds + ") {");
            indent++;
            printBlockInner(a.body());
            indent--;
            line("}");
        }
        indent--;
        line("}");
    }

    private String exprStr(Expr e) {
        return switch (e) {
            case Expr.Literal l -> literalStr(l);
            case Expr.Path p -> String.join("::", p.segments());
            case Expr.Binary b -> "(" + exprStr(b.left()) + " " + opStr(b.op()) + " " + exprStr(b.right()) + ")";
            case Expr.Member m -> exprStr(m.receiver()) + "." + m.name();
            case Expr.Call c ->
                exprStr(c.callee()) + "(" + c.args().stream().map(this::exprStr).collect(Collectors.joining(", "))
                        + ")";
            case Expr.TypeApply ta ->
                exprStr(ta.base())
                        + "["
                        + ta.typeArgs().stream().map(this::type).collect(Collectors.joining(", "))
                        + "]";
            case Expr.Index ix -> exprStr(ix.target()) + "[" + exprStr(ix.index()) + "]";
            case Expr.If i -> "if (" + exprStr(i.cond()) + ") { ... } else { ... }";
            case Expr.When w -> "when " + exprStr(w.scrutinee()) + " { ... }";
            case Expr.BlockExpr be -> "{ ... }";
        };
    }

    private static String literalStr(Expr.Literal l) {
        return switch (l.kind()) {
            case STRING -> "\"" + escapeString(l.text()) + "\"";
            case INT -> l.text();
            case BOOL -> l.text();
        };
    }

    private static String escapeString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String opStr(BinaryOp op) {
        return switch (op) {
            case PLUS -> "+";
            case MINUS -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case EQEQ -> "==";
            case NEQ -> "!=";
            case GE -> ">=";
            case LE -> "<=";
            case LT -> "<";
            case GT -> ">";
        };
    }

    private String superArgs(SuperRef s) {
        if (s.args().isEmpty()) {
            return "";
        }
        return "(" + s.args().stream().map(this::exprStr).collect(Collectors.joining(", ")) + ")";
    }

    private String fieldParam(FieldParam f) {
        String m = modStr(f.modifiers());
        return m + "let " + f.name() + ": " + type(f.type());
    }

    private static String modStr(java.util.EnumSet<Modifier> m) {
        if (m.isEmpty()) {
            return "";
        }
        return m.stream().map(Enum::name).map(String::toLowerCase).collect(Collectors.joining(" ")) + " ";
    }

    private String params(java.util.List<Param> ps) {
        return ps.stream()
                .map(p -> p.type().map(t -> p.name() + ": " + type(t)).orElse(p.name()))
                .collect(Collectors.joining(", "));
    }

    private String typeParams(java.util.List<TypeParam> tps) {
        if (tps.isEmpty()) {
            return "";
        }
        return "[" + tps.stream()
                .map(tp -> tp.name()
                        + tp.upperBound().map(b -> " <: " + type(b)).orElse(""))
                .collect(Collectors.joining(", "))
                + "]";
    }

    private String type(TypeRef t) {
        return switch (t) {
            case TypeRef.Named n -> n.name();
            case TypeRef.Path p -> String.join("::", p.segments());
            case TypeRef.Applied a ->
                type(a.base()) + "[" + a.args().stream().map(this::type).collect(Collectors.joining(", ")) + "]";
            case TypeRef.Array ar -> "[]" + type(ar.element());
        };
    }

    private void line(String s) {
        sb.append("  ".repeat(indent)).append(s).append('\n');
    }
}
