package dev.icxd.yakou.syntax;

/**
 * Lexical token kinds for Yakou. Keywords are prefixed with {@code KW_};
 * punctuation uses short names.
 */
public enum TokenKind {
    EOF,

    IDENT,
    INT_LITERAL,
    STRING_LITERAL,

    KW_USE,
    KW_TRAIT,
    KW_SEALED,
    KW_CLASS,
    KW_LET,
    KW_MUT,
    KW_PRIV,
    KW_PUB,
    KW_PKG,
    KW_FN,
    KW_OPEN,
    KW_OVERRIDE,
    KW_SELF,
    KW_IS,
    KW_WHEN,
    KW_ELSE,
    KW_IF,
    KW_TRUE,
    KW_FALSE,

    LPAREN,
    RPAREN,
    LBRACE,
    RBRACE,
    LBRACK,
    RBRACK,

    COMMA,
    SEMI,
    COLON,
    DOT,
    PLUS,
    MINUS,
    STAR,
    SLASH,
    EQ,
    EQ_EQ,
    BANG_EQ,

    LT,
    GT,
    LE,
    GE,

    COLON_COLON,
    LT_COLON,

    /** {@code #} — starts attribute syntax {@code #{…}} */
    HASH,
}
