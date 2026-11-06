package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

/**
 * The lexical token kinds of the Apexx DSL.
 *
 * <p>There is no token for {@code new}, imports, or arbitrary method calls on
 * objects. {@code .} only reads a field from a record or one of the fixed entity
 * fields the host exposes, which keeps the sandbox closed.</p>
 */
public enum TokenType {
    // Structural keywords
    MODULE, CATEGORY, DESCRIPTION, SETTING, VAR, LET, ON, FN,
    SLIDER, TOGGLE, MODE, RANGE, STEP, OF,

    // Statement keywords
    IF, ELSE, WHILE, FOR, IN, BREAK, CONTINUE, RETURN, AFTER,

    // Logical / unary word operators
    AND, OR, NOT,

    // Literals
    NUMBER, STRING, TRUE, FALSE, NULL, IDENT,

    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ, NEQ, LT, LTE, GT, GTE,
    ASSIGN,

    // Punctuation
    LBRACE, RBRACE, LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA, DOTDOT, DOT,

    // Statement / stream control
    NEWLINE, EOF
}
