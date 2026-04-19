package dev.icxd.yakou.parse;

import static dev.icxd.yakou.syntax.TokenKind.COLON;
import static dev.icxd.yakou.syntax.TokenKind.COLON_COLON;
import static dev.icxd.yakou.syntax.TokenKind.COMMA;
import static dev.icxd.yakou.syntax.TokenKind.DOT;
import static dev.icxd.yakou.syntax.TokenKind.EOF;
import static dev.icxd.yakou.syntax.TokenKind.EQ;
import static dev.icxd.yakou.syntax.TokenKind.GE;
import static dev.icxd.yakou.syntax.TokenKind.GT;
import static dev.icxd.yakou.syntax.TokenKind.HASH;
import static dev.icxd.yakou.syntax.TokenKind.IDENT;
import static dev.icxd.yakou.syntax.TokenKind.INT_LITERAL;
import static dev.icxd.yakou.syntax.TokenKind.KW_CLASS;
import static dev.icxd.yakou.syntax.TokenKind.KW_ELSE;
import static dev.icxd.yakou.syntax.TokenKind.KW_FALSE;
import static dev.icxd.yakou.syntax.TokenKind.KW_FN;
import static dev.icxd.yakou.syntax.TokenKind.KW_IF;
import static dev.icxd.yakou.syntax.TokenKind.KW_IS;
import static dev.icxd.yakou.syntax.TokenKind.KW_LET;
import static dev.icxd.yakou.syntax.TokenKind.KW_MUT;
import static dev.icxd.yakou.syntax.TokenKind.KW_OPEN;
import static dev.icxd.yakou.syntax.TokenKind.KW_OVERRIDE;
import static dev.icxd.yakou.syntax.TokenKind.KW_PKG;
import static dev.icxd.yakou.syntax.TokenKind.KW_PRIV;
import static dev.icxd.yakou.syntax.TokenKind.KW_PUB;
import static dev.icxd.yakou.syntax.TokenKind.KW_SELF;
import static dev.icxd.yakou.syntax.TokenKind.KW_SEALED;
import static dev.icxd.yakou.syntax.TokenKind.KW_TRAIT;
import static dev.icxd.yakou.syntax.TokenKind.KW_TRUE;
import static dev.icxd.yakou.syntax.TokenKind.KW_USE;
import static dev.icxd.yakou.syntax.TokenKind.KW_WHEN;
import static dev.icxd.yakou.syntax.TokenKind.LE;
import static dev.icxd.yakou.syntax.TokenKind.LBRACE;
import static dev.icxd.yakou.syntax.TokenKind.LBRACK;
import static dev.icxd.yakou.syntax.TokenKind.LPAREN;
import static dev.icxd.yakou.syntax.TokenKind.LT;
import static dev.icxd.yakou.syntax.TokenKind.LT_COLON;
import static dev.icxd.yakou.syntax.TokenKind.MINUS;
import static dev.icxd.yakou.syntax.TokenKind.PLUS;
import static dev.icxd.yakou.syntax.TokenKind.SLASH;
import static dev.icxd.yakou.syntax.TokenKind.STAR;
import static dev.icxd.yakou.syntax.TokenKind.RBRACE;
import static dev.icxd.yakou.syntax.TokenKind.RBRACK;
import static dev.icxd.yakou.syntax.TokenKind.RPAREN;
import static dev.icxd.yakou.syntax.TokenKind.SEMI;
import static dev.icxd.yakou.syntax.TokenKind.STRING_LITERAL;
import static dev.icxd.yakou.syntax.TokenKind.EQ_EQ;
import static dev.icxd.yakou.syntax.TokenKind.BANG_EQ;

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
import dev.icxd.yakou.syntax.Token;
import dev.icxd.yakou.syntax.TokenKind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public final class Parser {

    private final String fileName;
    private final List<Token> tokens;
    private int i;

    public Parser(String fileName, List<Token> tokens) {
        this.fileName = fileName;
        this.tokens = tokens;
    }

    public AstFile parseFile() {
        List<Item> items = new ArrayList<>();
        while (!check(EOF)) {
            items.add(parseItem());
        }
        return new AstFile(fileName, items);
    }

    private Item parseItem() {
        int itemStart = i;
        List<Attribute> attrs = parseLeadingAttributes();
        if (match(KW_USE)) {
            if (!attrs.isEmpty()) {
                throw error(peek(), "`use` items cannot carry attributes");
            }
            List<String> path = parseIdentPath();
            expect(SEMI);
            return new Item.Use(path, spanSince(itemStart));
        }
        if (match(KW_SEALED)) {
            expect(KW_TRAIT);
            return parseTrait(attrs, EnumSet.of(Modifier.SEALED), itemStart);
        }
        if (check(KW_TRAIT)) {
            advance();
            return parseTrait(attrs, EnumSet.noneOf(Modifier.class), itemStart);
        }
        if (match(KW_OPEN)) {
            expect(KW_CLASS);
            return parseClass(attrs, EnumSet.of(Modifier.OPEN), itemStart);
        }
        if (match(KW_PRIV)) {
            if (match(KW_PKG)) {
                return parsePkg(attrs, EnumSet.of(Modifier.PRIV), itemStart);
            }
            expect(KW_FN);
            return parseFn(attrs, EnumSet.of(Modifier.PRIV), itemStart);
        }
        if (match(KW_PUB)) {
            expect(KW_FN);
            return parseFn(attrs, EnumSet.of(Modifier.PUB), itemStart);
        }
        if (match(KW_PKG)) {
            return parsePkg(attrs, EnumSet.noneOf(Modifier.class), itemStart);
        }
        if (match(KW_CLASS)) {
            return parseClass(attrs, EnumSet.noneOf(Modifier.class), itemStart);
        }
        if (match(KW_FN)) {
            return parseFn(attrs, EnumSet.noneOf(Modifier.class), itemStart);
        }
        throw error(peek(), "expected item (use, trait, class, pkg, fn)");
    }

    /** Parses {@code #{ expr }} repeated zero or more times. */
    private List<Attribute> parseLeadingAttributes() {
        List<Attribute> out = new ArrayList<>();
        while (check(HASH)) {
            int aStart = i;
            advance();
            expect(LBRACE);
            Expr inner = parseExpr();
            expect(RBRACE);
            out.add(new Attribute(inner, spanSince(aStart)));
        }
        return out;
    }

    private Item.Trait parseTrait(List<Attribute> attrs, EnumSet<Modifier> mods, int itemStart) {
        String name = expect(IDENT).lexeme();
        List<TypeParam> tps = parseTypeParams();
        expect(LBRACE);
        List<MethodSig> methods = new ArrayList<>();
        while (!check(RBRACE) && !check(EOF)) {
            methods.add(parseTraitMethodSig());
        }
        expect(RBRACE);
        return new Item.Trait(attrs, mods, name, tps, methods, spanSince(itemStart));
    }

    private MethodSig parseTraitMethodSig() {
        int start = i;
        expect(KW_FN);
        String name = expect(IDENT).lexeme();
        expect(LPAREN);
        List<Param> params = parseParams();
        expect(RPAREN);
        expect(COLON);
        TypeRef ret = parseType();
        expect(SEMI);
        return new MethodSig(name, params, ret, spanSince(start));
    }

    private Item.Class parseClass(List<Attribute> attrs, EnumSet<Modifier> mods, int itemStart) {
        String name = expect(IDENT).lexeme();
        List<TypeParam> tps = parseTypeParams();
        expect(LPAREN);
        List<FieldParam> fields = parseFieldParams();
        expect(RPAREN);
        Optional<SuperRef> sup = Optional.empty();
        if (match(COLON)) {
            int supStart = i - 1;
            TypeRef ty = parseType();
            List<Expr> args = new ArrayList<>();
            if (match(LPAREN)) {
                args = parseExprList();
                expect(RPAREN);
            }
            sup = Optional.of(new SuperRef(ty, args, spanSince(supStart)));
        }
        if (match(SEMI)) {
            return new Item.Class(attrs, mods, name, tps, fields, sup, List.of(), true, spanSince(itemStart));
        }
        expect(LBRACE);
        List<ClassMethod> methods = new ArrayList<>();
        while (!check(RBRACE) && !check(EOF)) {
            methods.add(parseClassMethod());
        }
        expect(RBRACE);
        return new Item.Class(attrs, mods, name, tps, fields, sup, methods, false, spanSince(itemStart));
    }

    private ClassMethod parseClassMethod() {
        int start = i;
        List<Attribute> attrs = parseLeadingAttributes();
        EnumSet<Modifier> mm = EnumSet.noneOf(Modifier.class);
        while (true) {
            if (match(KW_PRIV)) {
                mm.add(Modifier.PRIV);
            } else if (match(KW_OVERRIDE)) {
                mm.add(Modifier.OVERRIDE);
            } else {
                break;
            }
        }
        expect(KW_FN);
        String name = expect(IDENT).lexeme();
        expect(LPAREN);
        List<Param> params = parseParams();
        expect(RPAREN);
        expect(COLON);
        TypeRef ret = parseType();
        FnBody body = parseFnBody();
        return new ClassMethod(attrs, mm, name, params, ret, body, spanSince(start));
    }

    private Item.Pkg parsePkg(List<Attribute> attrs, EnumSet<Modifier> mods, int itemStart) {
        String name = expect(IDENT).lexeme();
        expect(LBRACE);
        List<Item> inner = new ArrayList<>();
        while (!check(RBRACE) && !check(EOF)) {
            inner.add(parseItem());
        }
        expect(RBRACE);
        return new Item.Pkg(attrs, mods, name, inner, spanSince(itemStart));
    }

    private Item.Fn parseFn(List<Attribute> attrs, EnumSet<Modifier> mods, int itemStart) {
        String name = expect(IDENT).lexeme();
        expect(LPAREN);
        List<Param> params = parseParams();
        expect(RPAREN);
        expect(COLON);
        TypeRef ret = parseType();
        FnBody body = parseFnBody();
        return new Item.Fn(attrs, mods, name, params, ret, body, spanSince(itemStart));
    }

    private FnBody parseFnBody() {
        if (match(EQ)) {
            int start = i - 1;
            Expr e = parseExpr();
            expect(SEMI);
            return new FnBody.ExprEquals(e, spanSince(start));
        }
        return new FnBody.BlockBody(parseBlock());
    }

    private List<TypeParam> parseTypeParams() {
        if (!match(LBRACK)) {
            return List.of();
        }
        List<TypeParam> out = new ArrayList<>();
        if (!check(RBRACK)) {
            out.add(parseTypeParam());
            while (match(COMMA)) {
                out.add(parseTypeParam());
            }
        }
        expect(RBRACK);
        return out;
    }

    private TypeParam parseTypeParam() {
        int start = i;
        String name = expect(IDENT).lexeme();
        if (match(LT_COLON)) {
            return new TypeParam(name, Optional.of(parseType()), spanSince(start));
        }
        return new TypeParam(name, Optional.empty(), spanSince(start));
    }

    private List<FieldParam> parseFieldParams() {
        List<FieldParam> out = new ArrayList<>();
        if (check(RPAREN)) {
            return out;
        }
        while (true) {
            out.add(parseFieldParam());
            if (match(COMMA)) {
                if (check(RPAREN)) {
                    break; // trailing comma
                }
                continue;
            }
            break;
        }
        return out;
    }

    private FieldParam parseFieldParam() {
        int start = i;
        EnumSet<Modifier> m = EnumSet.noneOf(Modifier.class);
        if (match(KW_PRIV)) {
            m.add(Modifier.PRIV);
        }
        if (match(KW_MUT)) {
            m.add(Modifier.MUT);
            String name = expect(IDENT).lexeme();
            expect(COLON);
            TypeRef ty = parseType();
            return new FieldParam(m, name, ty, spanSince(start));
        }
        expect(KW_LET);
        String name = expect(IDENT).lexeme();
        expect(COLON);
        TypeRef ty = parseType();
        return new FieldParam(m, name, ty, spanSince(start));
    }

    private List<Param> parseParams() {
        List<Param> out = new ArrayList<>();
        if (check(RPAREN)) {
            return out;
        }
        out.add(parseParam());
        while (match(COMMA)) {
            out.add(parseParam());
        }
        return out;
    }

    private Param parseParam() {
        int start = i;
        String name = parseBindingName();
        if (match(COLON)) {
            TypeRef ty = parseType();
            return new Param(name, Optional.of(ty), spanSince(start));
        }
        return new Param(name, Optional.empty(), spanSince(start));
    }

    private String parseBindingName() {
        if (match(KW_SELF)) {
            return "self";
        }
        return expect(IDENT).lexeme();
    }

    private TypeRef parseType() {
        int start = i;
        if (match(LBRACK)) {
            if (!check(RBRACK)) {
                throw error(
                        peek(),
                        "array types use prefix `[]T`; for type application write the base before `[` (e.g. `List[str]`)");
            }
            expect(RBRACK);
            TypeRef elem = parseType();
            return new TypeRef.Array(elem, spanSince(start));
        }
        TypeRef base = parseTypeAtomic(start);
        if (match(LBRACK)) {
            List<TypeRef> args = new ArrayList<>();
            args.add(parseType());
            while (match(COMMA)) {
                args.add(parseType());
            }
            expect(RBRACK);
            return new TypeRef.Applied(base, args, spanSince(start));
        }
        return base;
    }

    /**
     * Non-generic head: {@code Foo} or {@code a::b::C} (no trailing {@code [T]} —
     * that is parsed in {@link #parseType()}).
     */
    private TypeRef parseTypeAtomic(int start) {
        List<String> path = parseIdentPath();
        Optional<SourceSpan> sp = spanSince(start);
        return path.size() == 1
                ? new TypeRef.Named(path.getFirst(), sp)
                : new TypeRef.Path(path, sp);
    }

    private List<String> parseIdentPath() {
        List<String> segs = new ArrayList<>();
        segs.add(expect(IDENT).lexeme());
        while (match(COLON_COLON)) {
            segs.add(expect(IDENT).lexeme());
        }
        return segs;
    }

    private Block parseBlock() {
        int start = i;
        expect(LBRACE);
        List<Stmt> stmts = new ArrayList<>();
        Optional<Expr> tail = Optional.empty();
        while (!check(RBRACE) && !check(EOF)) {
            if (check(KW_LET)) {
                stmts.add(parseLetStmt());
                expect(SEMI);
            } else {
                Expr e = parseExpr();
                if (match(SEMI)) {
                    stmts.add(new Stmt.ExprSemi(e, e.span()));
                } else if (check(RBRACE)) {
                    tail = Optional.of(e);
                    break;
                } else {
                    throw error(peek(), "expected ';' or '}' after expression");
                }
            }
        }
        expect(RBRACE);
        return new Block(stmts, tail, spanSince(start));
    }

    private Stmt.Let parseLetStmt() {
        int start = i;
        expect(KW_LET);
        String name = expect(IDENT).lexeme();
        Optional<TypeRef> ann = Optional.empty();
        if (match(COLON)) {
            ann = Optional.of(parseType());
        }
        expect(EQ);
        Expr value = parseExpr();
        return new Stmt.Let(name, ann, value, spanSince(start));
    }

    private Expr parseExpr() {
        return parseEquality();
    }

    private Expr parseEquality() {
        int exprStart = i;
        Expr left = parseComparison();
        while (true) {
            if (match(EQ_EQ)) {
                Expr right = parseComparison();
                left = new Expr.Binary(left, BinaryOp.EQEQ, right, spanSince(exprStart));
            } else if (match(BANG_EQ)) {
                Expr right = parseComparison();
                left = new Expr.Binary(left, BinaryOp.NEQ, right, spanSince(exprStart));
            } else {
                break;
            }
        }
        return left;
    }

    private Expr parseComparison() {
        int exprStart = i;
        Expr left = parseAdditive();
        while (true) {
            if (match(GE)) {
                Expr right = parseAdditive();
                left = new Expr.Binary(left, BinaryOp.GE, right, spanSince(exprStart));
            } else if (match(LE)) {
                Expr right = parseAdditive();
                left = new Expr.Binary(left, BinaryOp.LE, right, spanSince(exprStart));
            } else if (match(LT)) {
                Expr right = parseAdditive();
                left = new Expr.Binary(left, BinaryOp.LT, right, spanSince(exprStart));
            } else if (match(GT)) {
                Expr right = parseAdditive();
                left = new Expr.Binary(left, BinaryOp.GT, right, spanSince(exprStart));
            } else {
                break;
            }
        }
        return left;
    }

    private Expr parseAdditive() {
        int exprStart = i;
        Expr left = parseMultiplicative();
        while (true) {
            if (match(PLUS)) {
                Expr right = parseMultiplicative();
                left = new Expr.Binary(left, BinaryOp.PLUS, right, spanSince(exprStart));
            } else if (match(MINUS)) {
                Expr right = parseMultiplicative();
                left = new Expr.Binary(left, BinaryOp.MINUS, right, spanSince(exprStart));
            } else {
                break;
            }
        }
        return left;
    }

    private Expr parseMultiplicative() {
        int exprStart = i;
        Expr left = parsePostfix();
        while (true) {
            if (match(STAR)) {
                Expr right = parsePostfix();
                left = new Expr.Binary(left, BinaryOp.MUL, right, spanSince(exprStart));
            } else if (match(SLASH)) {
                Expr right = parsePostfix();
                left = new Expr.Binary(left, BinaryOp.DIV, right, spanSince(exprStart));
            } else {
                break;
            }
        }
        return left;
    }

    private Expr parsePostfix() {
        int chainStart = i;
        Expr e = parsePrimary();
        while (true) {
            if (check(LBRACK)) {
                advance(); // '['
                int innerCheckpoint = i;
                boolean parsedAsTypeArgs = false;
                List<TypeRef> targs = new ArrayList<>();
                try {
                    if (!check(RBRACK)) {
                        targs.add(parseType());
                        while (match(COMMA)) {
                            targs.add(parseType());
                        }
                    }
                    expect(RBRACK);
                    parsedAsTypeArgs = true;
                } catch (ParseException ignored) {
                    i = innerCheckpoint;
                }
                if (parsedAsTypeArgs) {
                    e = new Expr.TypeApply(e, targs, spanSince(chainStart));
                } else {
                    Expr idx = parseExpr();
                    expect(RBRACK);
                    e = new Expr.Index(e, idx, spanSince(chainStart));
                }
                continue;
            }
            if (match(LPAREN)) {
                List<Expr> args = parseExprList();
                expect(RPAREN);
                e = new Expr.Call(e, args, spanSince(chainStart));
            } else if (match(DOT)) {
                String name = expect(IDENT).lexeme();
                e = new Expr.Member(e, name, spanSince(chainStart));
            } else {
                break;
            }
        }
        return e;
    }

    private List<Expr> parseExprList() {
        List<Expr> out = new ArrayList<>();
        if (check(RPAREN)) {
            return out;
        }
        out.add(parseExpr());
        while (match(COMMA)) {
            out.add(parseExpr());
        }
        return out;
    }

    private Expr parsePrimary() {
        if (check(STRING_LITERAL)) {
            int start = i;
            Token t = peek();
            advance();
            return new Expr.Literal(Expr.LiteralKind.STRING, t.lexeme(), spanSince(start));
        }
        if (check(INT_LITERAL)) {
            int start = i;
            Token t = peek();
            advance();
            return new Expr.Literal(Expr.LiteralKind.INT, t.lexeme(), spanSince(start));
        }
        if (check(KW_TRUE)) {
            int start = i;
            advance();
            return new Expr.Literal(Expr.LiteralKind.BOOL, "true", spanSince(start));
        }
        if (check(KW_FALSE)) {
            int start = i;
            advance();
            return new Expr.Literal(Expr.LiteralKind.BOOL, "false", spanSince(start));
        }
        if (check(KW_IF)) {
            int start = i;
            advance();
            return parseIfExprAfterKw(start);
        }
        if (check(KW_WHEN)) {
            int start = i;
            advance();
            return parseWhenExprAfterKw(start);
        }
        if (check(LBRACE)) {
            int start = i;
            Block b = parseBlock();
            return new Expr.BlockExpr(b, spanSince(start));
        }
        if (match(LPAREN)) {
            Expr inner = parseExpr();
            expect(RPAREN);
            return inner;
        }
        return parsePathExpr();
    }

    private Expr parseIfExprAfterKw(int start) {
        expect(LPAREN);
        Expr cond = parseExpr();
        expect(RPAREN);
        Block thenB = parseBlock();
        expect(KW_ELSE);
        Block elseB = parseBlock();
        return new Expr.If(cond, thenB, elseB, spanSince(start));
    }

    private Expr parseWhenExprAfterKw(int start) {
        Expr s = parseExpr();
        expect(LBRACE);
        List<WhenArm> arms = new ArrayList<>();
        while (!check(RBRACE) && !check(EOF)) {
            int armStart = i;
            expect(KW_IS);
            String variant = expect(IDENT).lexeme();
            expect(LPAREN);
            List<String> binds = new ArrayList<>();
            if (!check(RPAREN)) {
                binds.add(expect(IDENT).lexeme());
                while (match(COMMA)) {
                    binds.add(expect(IDENT).lexeme());
                }
            }
            expect(RPAREN);
            Block body = parseBlock();
            arms.add(new WhenArm(variant, binds, body, spanSince(armStart)));
        }
        expect(RBRACE);
        return new Expr.When(s, arms, spanSince(start));
    }

    private Expr parsePathExpr() {
        int start = i;
        List<String> segs = new ArrayList<>();
        segs.add(parsePathSegment());
        while (match(COLON_COLON)) {
            segs.add(expect(IDENT).lexeme());
        }
        return new Expr.Path(segs, spanSince(start));
    }

    private String parsePathSegment() {
        if (match(KW_SELF)) {
            return "self";
        }
        return expect(IDENT).lexeme();
    }

    private Optional<SourceSpan> spanSince(int startTokenIndex) {
        if (startTokenIndex < 0 || startTokenIndex >= tokens.size()) {
            return Optional.empty();
        }
        if (i <= startTokenIndex) {
            return Optional.empty();
        }
        int endIdx = i - 1;
        Token a = tokens.get(startTokenIndex);
        Token b = tokens.get(endIdx);
        return Optional.of(SourceSpan.between(a, b));
    }

    private Token peek() {
        return tokens.get(i);
    }

    private boolean check(TokenKind k) {
        return peek().kind() == k;
    }

    private boolean match(TokenKind k) {
        if (check(k)) {
            advance();
            return true;
        }
        return false;
    }

    private void advance() {
        if (!check(EOF)) {
            i++;
        }
    }

    private Token expect(TokenKind k) {
        Token t = peek();
        if (t.kind() != k) {
            throw error(t, "expected " + k + ", found " + t.kind());
        }
        advance();
        return t;
    }

    private ParseException error(Token at, String msg) {
        return new ParseException(fileName, at, msg);
    }
}
