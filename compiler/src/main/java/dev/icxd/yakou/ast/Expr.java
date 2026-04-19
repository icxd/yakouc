package dev.icxd.yakou.ast;

import dev.icxd.yakou.syntax.SourceSpan;

import java.util.List;
import java.util.Optional;

public sealed interface Expr
                permits Expr.Literal,
                Expr.Path,
                Expr.Binary,
                Expr.Member,
                Expr.Call,
                Expr.TypeApply,
                Expr.Index,
                Expr.If,
                Expr.When,
                Expr.BlockExpr {

        Optional<SourceSpan> span();

        enum LiteralKind {
                STRING,
                INT,
                BOOL,
        }

        record Literal(LiteralKind kind, String text, Optional<SourceSpan> span) implements Expr {
        }

        /** {@code a::b::c} (expression position, e.g. call target). */
        record Path(List<String> segments, Optional<SourceSpan> span) implements Expr {
        }

        record Binary(Expr left, BinaryOp op, Expr right, Optional<SourceSpan> span) implements Expr {
        }

        record Member(Expr receiver, String name, Optional<SourceSpan> span) implements Expr {
        }

        record Call(Expr callee, List<Expr> args, Optional<SourceSpan> span) implements Expr {
        }

        /** {@code Path[A, B]} before a call, e.g. {@code Ok[User, ParseErr](value)}. */
        record TypeApply(Expr base, List<TypeRef> typeArgs, Optional<SourceSpan> span) implements Expr {
        }

        /** Indexing: {@code target[index]} (parsed after type-args form fails). */
        record Index(Expr target, Expr index, Optional<SourceSpan> span) implements Expr {
        }

        record If(Expr cond, Block thenBlock, Block elseBlock, Optional<SourceSpan> span)
                        implements Expr {
        }

        /**
         * Pattern match on a sealed trait or variant nominal. Arms are checked in
         * order;
         * when the scrutinee’s static type is a sealed trait, arms must cover every
         * variant class (see typechecker). Bindings match the variant constructor
         * fields
         * left-to-right ({@code is Ok(v)}, {@code is Pair(x, y)}, {@code is Unit()}).
         */
        record When(Expr scrutinee, List<WhenArm> arms, Optional<SourceSpan> span) implements Expr {
        }

        /** Braced block in expression position. */
        record BlockExpr(Block block, Optional<SourceSpan> span) implements Expr {
        }
}
