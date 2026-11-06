package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

/**
 * One lexical token. Immutable. Carries its source {@code line} for error
 * messages (every AST node ultimately traces back to one of these).
 */
public final class Token {
    public final TokenType type;
    /** Raw text for IDENT / STRING (the decoded string value for STRING). */
    public final String text;
    /** Parsed numeric value for NUMBER tokens; 0 otherwise. */
    public final double num;
    public final int line;

    public Token(TokenType type, String text, double num, int line) {
        this.type = type;
        this.text = text;
        this.num = num;
        this.line = line;
    }

    @Override
    public String toString() {
        if (type == TokenType.NUMBER) return type + "(" + num + ")";
        if (type == TokenType.IDENT || type == TokenType.STRING) return type + "(" + text + ")";
        return type.toString();
    }
}
