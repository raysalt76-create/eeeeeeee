package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written single-pass tokenizer for the Apexx DSL.
 *
 * <p>Newlines are significant (statement terminators), so they are emitted as
 * {@link TokenType#NEWLINE} tokens — but runs of blank lines / comments collapse
 * to a single NEWLINE so the parser can treat them uniformly. {@code #} begins a
 * comment to end-of-line. Strings are double-quoted with {@code \"}, {@code \\},
 * {@code \n}, {@code \t} escapes.</p>
 *
 * <p>Tokenization happens ONCE at compile time (never per tick), so simplicity
 * beats micro-optimization here.</p>
 */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();
    static {
        KEYWORDS.put("module", TokenType.MODULE);
        KEYWORDS.put("category", TokenType.CATEGORY);
        KEYWORDS.put("description", TokenType.DESCRIPTION);
        KEYWORDS.put("setting", TokenType.SETTING);
        KEYWORDS.put("var", TokenType.VAR);
        KEYWORDS.put("let", TokenType.LET);
        KEYWORDS.put("on", TokenType.ON);
        KEYWORDS.put("fn", TokenType.FN);
        KEYWORDS.put("after", TokenType.AFTER);
        KEYWORDS.put("slider", TokenType.SLIDER);
        KEYWORDS.put("toggle", TokenType.TOGGLE);
        KEYWORDS.put("mode", TokenType.MODE);
        KEYWORDS.put("range", TokenType.RANGE);
        KEYWORDS.put("step", TokenType.STEP);
        KEYWORDS.put("of", TokenType.OF);
        KEYWORDS.put("if", TokenType.IF);
        KEYWORDS.put("else", TokenType.ELSE);
        KEYWORDS.put("while", TokenType.WHILE);
        KEYWORDS.put("for", TokenType.FOR);
        KEYWORDS.put("in", TokenType.IN);
        KEYWORDS.put("break", TokenType.BREAK);
        KEYWORDS.put("continue", TokenType.CONTINUE);
        KEYWORDS.put("return", TokenType.RETURN);
        KEYWORDS.put("and", TokenType.AND);
        KEYWORDS.put("or", TokenType.OR);
        KEYWORDS.put("not", TokenType.NOT);
        KEYWORDS.put("true", TokenType.TRUE);
        KEYWORDS.put("false", TokenType.FALSE);
        KEYWORDS.put("null", TokenType.NULL);
    }

    private final String src;
    private int pos = 0;
    private int line = 1;
    private final List<Token> out = new ArrayList<>();

    public Lexer(String source) {
        this.src = source == null ? "" : source;
    }

    public List<Token> tokenize() {
        while (!atEnd()) {
            char c = peek();

            // Whitespace (not newline)
            if (c == ' ' || c == '\t' || c == '\r') { pos++; continue; }

            // Newline → statement terminator (collapse runs)
            if (c == '\n') {
                emitNewline();
                pos++;
                line++;
                continue;
            }

            // Comment to end of line
            if (c == '#') {
                while (!atEnd() && peek() != '\n') pos++;
                continue;
            }

            if (c == '"') { string(); continue; }

            if (isDigit(c) || (c == '.' && isDigit(peekAt(1)))) { number(); continue; }

            if (isIdentStart(c)) { identifier(); continue; }

            symbol(c);
        }

        emitNewline(); // ensure a terminator before EOF
        out.add(new Token(TokenType.EOF, "", 0, line));
        return out;
    }

    // ── Token producers ────────────────────────────────────────────────────

    private void string() {
        int startLine = line;
        pos++; // consume opening quote
        StringBuilder sb = new StringBuilder();
        while (!atEnd() && peek() != '"') {
            char c = peek();
            if (c == '\n') line++; // allow (rare) multi-line; keep line count honest
            if (c == '\\') {
                pos++;
                if (atEnd()) break;
                char e = peek();
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(e); break;
                }
                pos++;
                continue;
            }
            sb.append(c);
            pos++;
        }
        if (atEnd()) {
            throw new ScriptException("Unterminated string", startLine);
        }
        pos++; // consume closing quote
        out.add(new Token(TokenType.STRING, sb.toString(), 0, startLine));
    }

    private void number() {
        int start = pos;
        if (peek() == '0' && (peekAt(1) == 'x' || peekAt(1) == 'X') && isHex(peekAt(2))) {
            pos += 2;
            int digits = pos;
            while (!atEnd() && isHex(peek())) pos++;
            String hex = src.substring(digits, pos);
            if (hex.length() > 8) throw new ScriptException("Hex number too long: 0x" + hex, line);
            out.add(new Token(TokenType.NUMBER, src.substring(start, pos), (double) Long.parseLong(hex, 16), line));
            return;
        }
        while (!atEnd() && isDigit(peek())) pos++;
        // fractional part — but NOT the ".." range operator
        if (peek() == '.' && peekAt(1) != '.' && isDigit(peekAt(1))) {
            pos++; // consume '.'
            while (!atEnd() && isDigit(peek())) pos++;
        }
        String lexeme = src.substring(start, pos);
        double value;
        try {
            value = Double.parseDouble(lexeme);
        } catch (NumberFormatException e) {
            throw new ScriptException("Invalid number: " + lexeme, line);
        }
        out.add(new Token(TokenType.NUMBER, lexeme, value, line));
    }

    private void identifier() {
        int start = pos;
        while (!atEnd() && isIdentPart(peek())) pos++;
        String word = src.substring(start, pos);
        TokenType kw = KEYWORDS.get(word);
        if (kw != null) {
            out.add(new Token(kw, word, 0, line));
        } else {
            out.add(new Token(TokenType.IDENT, word, 0, line));
        }
    }

    private void symbol(char c) {
        switch (c) {
            case '{': add(TokenType.LBRACE); return;
            case '}': add(TokenType.RBRACE); return;
            case '(': add(TokenType.LPAREN); return;
            case '[': add(TokenType.LBRACKET); return;
            case ']': add(TokenType.RBRACKET); return;
            case ')': add(TokenType.RPAREN); return;
            case ',': add(TokenType.COMMA); return;
            case '+': add(TokenType.PLUS); return;
            case '-': add(TokenType.MINUS); return;
            case '*': add(TokenType.STAR); return;
            case '/': add(TokenType.SLASH); return;
            case '%': add(TokenType.PERCENT); return;
            case '.':
                if (peekAt(1) == '.') { out.add(new Token(TokenType.DOTDOT, "..", 0, line)); pos += 2; return; }
                add(TokenType.DOT); return;
            case '=':
                if (peekAt(1) == '=') { out.add(new Token(TokenType.EQ, "==", 0, line)); pos += 2; return; }
                add(TokenType.ASSIGN); return;
            case '!':
                if (peekAt(1) == '=') { out.add(new Token(TokenType.NEQ, "!=", 0, line)); pos += 2; return; }
                throw new ScriptException("Unexpected '!' (did you mean 'not' or '!=')", line);
            case '<':
                if (peekAt(1) == '=') { out.add(new Token(TokenType.LTE, "<=", 0, line)); pos += 2; return; }
                add(TokenType.LT); return;
            case '>':
                if (peekAt(1) == '=') { out.add(new Token(TokenType.GTE, ">=", 0, line)); pos += 2; return; }
                add(TokenType.GT); return;
            default:
                throw new ScriptException("Unexpected character '" + c + "'", line);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void add(TokenType t) {
        out.add(new Token(t, String.valueOf(peek()), 0, line));
        pos++;
    }

    private void emitNewline() {
        if (out.isEmpty()) return;
        TokenType last = out.get(out.size() - 1).type;
        if (last == TokenType.NEWLINE) return; // collapse runs
        out.add(new Token(TokenType.NEWLINE, "\\n", 0, line));
    }

    private boolean atEnd() { return pos >= src.length(); }
    private char peek() { return atEnd() ? '\0' : src.charAt(pos); }
    private char peekAt(int n) { int i = pos + n; return i >= src.length() ? '\0' : src.charAt(i); }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isHex(char c) { return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'); }
    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }
    private static boolean isIdentPart(char c) { return isIdentStart(c) || isDigit(c); }
}
