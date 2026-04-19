package dev.icxd.yakou.emit;

import dev.icxd.yakou.ast.Block;
import dev.icxd.yakou.ast.Expr;
import dev.icxd.yakou.ast.Stmt;
import dev.icxd.yakou.typeck.Ty;

import org.objectweb.asm.Opcodes;

final class BlockAsm {

    static void emitBlockExpr(ExprAsm ex, Expr.BlockExpr be) {
        emitBlock(ex, be.block(), ex.locals);
    }

    static void emitBlock(ExprAsm ex, Block block, LocalTable locals) {
        for (Stmt st : block.statements()) {
            switch (st) {
                case Stmt.Let l -> {
                    Ty vt = ex.requireType(l.value());
                    ex.emitExpr(l.value());
                    String name = l.name();
                    locals.bindLocal(name, vt);
                    int slot = locals.slot(name);
                    TyStack sk = TyStack.of(vt);
                    ex.mv.visitVarInsn(sk.storeOpcode(), slot);
                }
                case Stmt.ExprSemi es -> {
                    ex.emitExpr(es.expr());
                    Ty t = ex.requireType(es.expr());
                    if (!TyStack.isVoid(t)) {
                        ex.mv.visitInsn(popInsn(t));
                    }
                }
            }
        }
        if (block.trailingExpr().isPresent()) {
            ex.emitExpr(block.trailingExpr().get());
        }
    }

    static void emitBlockValue(ExprAsm ex, Block block, LocalTable locals) {
        for (Stmt st : block.statements()) {
            switch (st) {
                case Stmt.Let l -> {
                    Ty vt = ex.requireType(l.value());
                    ex.emitExpr(l.value());
                    locals.bindLocal(l.name(), vt);
                    int slot = locals.slot(l.name());
                    TyStack sk = TyStack.of(vt);
                    ex.mv.visitVarInsn(sk.storeOpcode(), slot);
                }
                case Stmt.ExprSemi es -> {
                    ex.emitExpr(es.expr());
                    Ty t = ex.requireType(es.expr());
                    if (!TyStack.isVoid(t)) {
                        ex.mv.visitInsn(popInsn(t));
                    }
                }
            }
        }
        if (block.trailingExpr().isEmpty()) {
            throw new CodegenException("block must produce a value (trailing expression)");
        }
        ex.emitExpr(block.trailingExpr().get());
    }

    private static int popInsn(Ty t) {
        TyStack sk = TyStack.of(t);
        return switch (sk) {
            case LONG, DOUBLE -> Opcodes.POP2;
            case INT, FLOAT, REF -> Opcodes.POP;
        };
    }
}
