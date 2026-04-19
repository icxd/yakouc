package dev.icxd.yakou.syntax;

import static dev.icxd.yakou.syntax.TokenKind.COLON;
import static dev.icxd.yakou.syntax.TokenKind.COLON_COLON;
import static dev.icxd.yakou.syntax.TokenKind.COMMA;
import static dev.icxd.yakou.syntax.TokenKind.DOT;
import static dev.icxd.yakou.syntax.TokenKind.EOF;
import static dev.icxd.yakou.syntax.TokenKind.EQ;
import static dev.icxd.yakou.syntax.TokenKind.EQ_EQ;
import static dev.icxd.yakou.syntax.TokenKind.BANG_EQ;
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
import static dev.icxd.yakou.syntax.TokenKind.KW_SEALED;
import static dev.icxd.yakou.syntax.TokenKind.KW_SELF;
import static dev.icxd.yakou.syntax.TokenKind.KW_TRAIT;
import static dev.icxd.yakou.syntax.TokenKind.KW_TRUE;
import static dev.icxd.yakou.syntax.TokenKind.KW_USE;
import static dev.icxd.yakou.syntax.TokenKind.KW_WHEN;
import static dev.icxd.yakou.syntax.TokenKind.LBRACE;
import static dev.icxd.yakou.syntax.TokenKind.LBRACK;
import static dev.icxd.yakou.syntax.TokenKind.LE;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans Yakou source into {@link Token}s. Skips line comments and slash-star
 * block comments.
 */
public final class Lexer {

    private static final Map<String, TokenKind> KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put("use", KW_USE);
        KEYWORDS.put("trait", KW_TRAIT);
        KEYWORDS.put("sealed", KW_SEALED);
        KEYWORDS.put("class", KW_CLASS);
        KEYWORDS.put("let", KW_LET);
        KEYWORDS.put("mut", KW_MUT);
        KEYWORDS.put("priv", KW_PRIV);
        KEYWORDS.put("pub", KW_PUB);
        KEYWORDS.put("pkg", KW_PKG);
        KEYWORDS.put("fn", KW_FN);
        KEYWORDS.put("open", KW_OPEN);
        KEYWORDS.put("override", KW_OVERRIDE);
        KEYWORDS.put("self", KW_SELF);
        KEYWORDS.put("is", KW_IS);
        KEYWORDS.put("when", KW_WHEN);
        KEYWORDS.put("else", KW_ELSE);
        KEYWORDS.put("if", KW_IF);
        KEYWORDS.put("true", KW_TRUE);
        KEYWORDS.put("false", KW_FALSE);
    }

    private final String src;
    private final String fileName;
    private int pos;
    private int line = 1;
    private int col = 1;

    private int tokenStartOffset;
    private int tokenStartLine = 1;
    private int tokenStartCol = 1;

    public Lexer(String source, String fileName) {
        this.src = source;
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }

    /** Returns all tokens including a final {@link TokenKind#EOF}. */
    public List<Token> tokenizeAll() {
        List<Token> out = new ArrayList<>(src.length() / 8);
        while (true) {
            Token t = nextToken();
            out.add(t);
            if (t.kind() == EOF) {
                return List.copyOf(out);
            }
        }
    }

    public Token nextToken() {
        skipWhitespaceAndComments();
        if (eof()) {
            return token(EOF, "");
        }
        markTokenStart();
        char c = peek();
        if (isIdentStart(c)) {
            return readIdentifierOrKeyword();
        }
        if (isDigit(c)) {
            return readIntLiteral();
        }
        if (c == '"') {
            return readStringLiteral();
        }
        return readPunctuation();
    }

    private Token readPunctuation() {
        char c = peek();
        switch (c) {
            case '(' -> {
                advance();
                return token(LPAREN, "(");
            }
            case ')' -> {
                advance();
                return token(RPAREN, ")");
            }
            case '{' -> {
                advance();
                return token(LBRACE, "{");
            }
            case '}' -> {
                advance();
                return token(RBRACE, "}");
            }
            case '[' -> {
                advance();
                return token(LBRACK, "[");
            }
            case ']' -> {
                advance();
                return token(RBRACK, "]");
            }
            case ',' -> {
                advance();
                return token(COMMA, ",");
            }
            case ';' -> {
                advance();
                return token(SEMI, ";");
            }
            case '+' -> {
                advance();
                return token(PLUS, "+");
            }
            case '-' -> {
                advance();
                return token(MINUS, "-");
            }
            case '*' -> {
                advance();
                return token(STAR, "*");
            }
            case '/' -> {
                advance();
                return token(SLASH, "/");
            }
            case '#' -> {
                advance();
                return token(HASH, "#");
            }
            case '.' -> {
                advance();
                return token(DOT, ".");
            }
            case ':' -> {
                advance();
                if (!eof() && peek() == ':') {
                    advance();
                    return token(COLON_COLON, "::");
                }
                return token(COLON, ":");
            }
            case '=' -> {
                advance();
                if (!eof() && peek() == '=') {
                    advance();
                    return token(EQ_EQ, "==");
                }
                return token(EQ, "=");
            }
            case '!' -> {
                advance();
                if (!eof() && peek() == '=') {
                    advance();
                    return token(BANG_EQ, "!=");
                }
                throw new LexException(fileName, src, position(), "unexpected character '!'");
            }
            case '<' -> {
                advance();
                if (!eof() && peek() == '=') {
                    advance();
                    return token(LE, "<=");
                }
                if (!eof() && peek() == ':') {
                    advance();
                    return token(LT_COLON, "<:");
                }
                return token(LT, "<");
            }
            case '>' -> {
                advance();
                if (!eof() && peek() == '=') {
                    advance();
                    return token(GE, ">=");
                }
                return token(GT, ">");
            }
            default -> throw new LexException(
                    fileName,
                    src,
                    position(),
                    "unexpected character '" + c + "' (\\u" + Integer.toHexString(c) + ")");
        }
    }

    private Token readIdentifierOrKeyword() {
        int start = pos;
        advance();
        while (!eof() && isIdentContinue(peek())) {
            advance();
        }
        String text = src.substring(start, pos);
        TokenKind kind = KEYWORDS.get(text);
        if (kind == null) {
            kind = IDENT;
        }
        return token(kind, text);
    }

    private Token readIntLiteral() {
        int start = pos;
        while (!eof() && isDigit(peek())) {
            advance();
        }
        return token(INT_LITERAL, src.substring(start, pos));
    }

    private Token readStringLiteral() {
        advance(); // opening "
        StringBuilder sb = new StringBuilder();
        while (!eof() && peek() != '"') {
            if (peek() == '\\') {
                advance();
                if (eof()) {
                    throw new LexException(fileName, src, position(), "unterminated string literal");
                }
                char e = peek();
                advance();
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    default -> throw new LexException(
                            fileName, src, position(), "invalid escape sequence '\\" + e + "'");
                }
            } else if (peek() == '\n' || peek() == '\r') {
                throw new LexException(
                        fileName, src, position(), "unescaped newline in string literal");
            } else {
                sb.append(peek());
                advance();
            }
        }
        if (eof()) {
            // Point at the opening `"`, not past it — clearer for a missing closing quote.
            throw new LexException(
                    fileName,
                    src,
                    new SourcePosition(tokenStartLine, tokenStartCol, tokenStartOffset),
                    "unterminated string literal");
        }
        advance(); // closing "
        return token(STRING_LITERAL, sb.toString());
    }

    private void skipWhitespaceAndComments() {
        while (!eof()) {
            char c = peek();
            if (isWhitespace(c)) {
                advance();
                continue;
            }
            if (c == '/' && peek(1) == '/') {
                advance();
                advance();
                while (!eof() && peek() != '\n' && peek() != '\r') {
                    advance();
                }
                continue;
            }
            if (c == '/' && peek(1) == '*') {
                advance();
                advance();
                boolean closed = false;
                while (!eof()) {
                    if (peek() == '*' && peek(1) == '/') {
                        advance();
                        advance();
                        closed = true;
                        break;
                    }
                    advance();
                }
                if (!closed) {
                    throw new LexException(fileName, src, position(), "unterminated block comment");
                }
                continue;
            }
            break;
        }
    }

    private void markTokenStart() {
        tokenStartOffset = pos;
        tokenStartLine = line;
        tokenStartCol = col;
    }

    private Token token(TokenKind kind, String lexeme) {
        return new Token(kind, lexeme, new SourcePosition(tokenStartLine, tokenStartCol, tokenStartOffset));
    }

    private SourcePosition position() {
        return new SourcePosition(line, col, pos);
    }

    private boolean eof() {
        return pos >= src.length();
    }

    private char peek() {
        return src.charAt(pos);
    }

    private char peek(int ahead) {
        int i = pos + ahead;
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private void advance() {
        if (eof()) {
            return;
        }
        char c = src.charAt(pos++);
        if (c == '\r') {
            if (!eof() && peek() == '\n') {
                pos++;
            }
            line++;
            col = 1;
        } else if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentContinue(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
