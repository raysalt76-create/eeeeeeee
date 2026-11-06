package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for the Apexx DSL. Produces a {@link ModuleDecl}.
 *
 * <pre>
 * module   := 'module' STRING '{' member* '}'
 * member   := 'category' IDENT | 'description' STRING | 'type' (toggle|oneshot|trigger)
 *           | 'key' STRING | setting | 'var' IDENT '=' expr | handler | fn
 * setting  := 'setting' ( 'slider' IDENT '=' num 'range' num '..' num ['step' num] ['jitter' num]
 *           | 'toggle' IDENT '=' bool ['bindable'] | 'mode' IDENT '=' STRING 'of' STRING (',' STRING)*
 *           | 'color' IDENT '=' NUMBER ) [STRING] ['if' STRING]
 * handler  := 'on' IDENT ['(' (IDENT|NUMBER) ')'] block
 * fn       := 'fn' IDENT '(' [IDENT (',' IDENT)*] ')' block
 * stmt     := let | assign | if | while | for | 'after' expr block | break | continue | 'return' [expr] | expr
 * postfix  := primary ('.' IDENT | '[' expr ']')*
 * </pre>
 *
 * <p>Statements are newline / {@code }}-terminated. Errors throw {@link ScriptException}
 * with a line number, quarantining exactly one script.</p>
 */
public final class Parser {

    private final List<Token> tokens;
    private int idx = 0;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static ModuleDecl parse(String source) {
        return new Parser(new Lexer(source).tokenize()).parseModule();
    }

    // ── module ──────────────────────────────────────────────────────────────

    public ModuleDecl parseModule() {
        skipNewlines();
        int line = peek().line;
        expect(TokenType.MODULE, "expected 'module'");
        Token nameTok = expect(TokenType.STRING, "expected module name string");
        expect(TokenType.LBRACE, "expected '{' after module name");

        String category = null;
        String description = null;
        String scriptType = null;
        String defaultKey = null;
        List<SettingDecl> settings = new ArrayList<>();
        List<VarDecl> vars = new ArrayList<>();
        List<Handler> handlers = new ArrayList<>();
        List<FnDecl> functions = new ArrayList<>();

        skipNewlines();
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            if (check(TokenType.CATEGORY)) {
                advance();
                category = expect(TokenType.IDENT, "expected category identifier").text;
            } else if (check(TokenType.DESCRIPTION)) {
                advance();
                description = expect(TokenType.STRING, "expected description string").text;
            } else if (checkWord("type")) {
                advance();
                Token t = expect(TokenType.IDENT, "expected toggle, oneshot or trigger after 'type'");
                if (!t.text.equals("toggle") && !t.text.equals("oneshot") && !t.text.equals("trigger")) {
                    throw new ScriptException("unknown script type '" + t.text + "' (use toggle, oneshot or trigger)", t.line);
                }
                scriptType = t.text;
            } else if (checkWord("key")) {
                advance();
                Token k = expect(TokenType.STRING, "expected a key name string, e.g. key \"R\"");
                if (ScriptKeys.parse(k.text) == ScriptKeys.UNKNOWN) {
                    throw new ScriptException("unknown key \"" + k.text + "\"", k.line);
                }
                defaultKey = k.text;
            } else if (check(TokenType.SETTING)) {
                settings.add(parseSetting());
            } else if (check(TokenType.VAR)) {
                vars.add(parseVarDecl());
            } else if (check(TokenType.ON)) {
                handlers.add(parseHandler());
            } else if (check(TokenType.FN)) {
                functions.add(parseFn());
            } else {
                throw err("unexpected '" + peek().text + "' in module body");
            }
            skipNewlines();
        }
        expect(TokenType.RBRACE, "expected '}' to close module");
        skipNewlines();
        if (!check(TokenType.EOF)) {
            throw err("unexpected content after module block");
        }
        return new ModuleDecl(nameTok.text, category, description, settings, vars, handlers, functions,
                scriptType, defaultKey, line);
    }

    // ── setting ─────────────────────────────────────────────────────────────

    private SettingDecl parseSetting() {
        int line = peek().line;
        expect(TokenType.SETTING, "expected 'setting'");
        SettingDecl decl;

        if (check(TokenType.SLIDER)) {
            advance();
            String name = ident("slider name");
            expect(TokenType.ASSIGN, "expected '=' in slider setting");
            double def = numberLiteral("slider default value");
            expect(TokenType.RANGE, "expected 'range' in slider setting");
            double lo = numberLiteral("slider range low");
            expect(TokenType.DOTDOT, "expected '..' in slider range");
            double hi = numberLiteral("slider range high");
            double step = 1.0;
            if (check(TokenType.STEP)) {
                advance();
                step = numberLiteral("slider step");
            }
            decl = SettingDecl.slider(name, def, lo, hi, step, line);
            if (checkWord("jitter")) {
                advance();
                double j = numberLiteral("jitter (Gaussian StDev)");
                if (j < 0) throw new ScriptException("jitter must be 0 or more", line);
                decl.jitter = j;
            }
        } else if (check(TokenType.TOGGLE)) {
            advance();
            String name = ident("toggle name");
            expect(TokenType.ASSIGN, "expected '=' in toggle setting");
            boolean def = boolLiteral("toggle default value");
            decl = SettingDecl.toggle(name, def, line);
            if (checkWord("bindable")) {
                advance();
                decl.bindable = true;
            }
        } else if (check(TokenType.MODE)) {
            advance();
            String name = ident("mode name");
            expect(TokenType.ASSIGN, "expected '=' in mode setting");
            Token def = expect(TokenType.STRING, "expected default mode string");
            expect(TokenType.OF, "expected 'of' in mode setting");
            List<String> modes = new ArrayList<>();
            modes.add(expect(TokenType.STRING, "expected a mode option string").text);
            while (check(TokenType.COMMA)) {
                advance();
                modes.add(expect(TokenType.STRING, "expected a mode option string").text);
            }
            if (!modes.contains(def.text)) {
                throw new ScriptException("mode default \"" + def.text + "\" is not one of the options", line);
            }
            decl = SettingDecl.mode(name, def.text, modes, line);
        } else if (checkWord("color")) {
            advance();
            String name = ident("color name");
            expect(TokenType.ASSIGN, "expected '=' in color setting");
            Token n = expect(TokenType.NUMBER, "expected a colour number such as 0xFF8800");
            long v = (long) n.num;
            // 0xRRGGBB (6 hex digits or fewer) is opaque; 8 digits carry their own alpha.
            if (n.text.length() <= 8) v |= 0xFF000000L;
            decl = SettingDecl.color(name, (int) v, line);
        } else {
            throw err("expected 'slider', 'toggle', 'mode' or 'color' after 'setting'");
        }

        if (check(TokenType.STRING)) {
            decl.description = advance().text;
        }
        if (check(TokenType.IF)) {
            advance();
            decl.dependsOn = expect(TokenType.STRING, "expected a condition string after 'if', e.g. if \"enabled\"").text;
        }
        terminator();
        return decl;
    }

    private VarDecl parseVarDecl() {
        int line = peek().line;
        expect(TokenType.VAR, "expected 'var'");
        String name = ident("var name");
        expect(TokenType.ASSIGN, "expected '=' in var declaration");
        Expr init = expression();
        terminator();
        return new VarDecl(name, init, line);
    }

    private Handler parseHandler() {
        int line = peek().line;
        expect(TokenType.ON, "expected 'on'");
        Token ev = expect(TokenType.IDENT, "expected event name after 'on'");
        String name = ev.text;
        if (!ScriptEventCatalog.isEvent(name)) {
            throw new ScriptException("unknown event '" + name + "' (see the Events list in the reference)", ev.line);
        }
        String param = null;
        double interval = 0;
        if (check(TokenType.LPAREN)) {
            advance();
            if (name.equals("every")) {
                interval = numberLiteral("interval in ms, e.g. on every(250)");
                if (interval < 1) throw new ScriptException("every() interval must be at least 1 ms", ev.line);
            } else {
                if (ScriptEventCatalog.isLifecycle(name)) {
                    throw new ScriptException("'" + name + "' has no event value; write 'on " + name + " { ... }'", ev.line);
                }
                param = ident("event variable name, e.g. on " + name + "(ev)");
            }
            expect(TokenType.RPAREN, "expected ')'");
        } else if (name.equals("every")) {
            throw new ScriptException("'every' needs an interval: on every(250) { ... }", ev.line);
        }
        Block body = block();
        EventKind kind = switch (name) {
            case "enable" -> EventKind.ENABLE;
            case "disable" -> EventKind.DISABLE;
            case "tick" -> EventKind.TICK;
            case "render" -> EventKind.RENDER;
            case "frame" -> EventKind.FRAME;
            case "trigger" -> EventKind.TRIGGER;
            case "every" -> EventKind.EVERY;
            default -> EventKind.EVENT;
        };
        return new Handler(kind, name, param, interval, body, line);
    }

    private FnDecl parseFn() {
        int line = peek().line;
        expect(TokenType.FN, "expected 'fn'");
        String name = ident("function name");
        expect(TokenType.LPAREN, "expected '(' after function name");
        List<String> params = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            params.add(ident("parameter name"));
            while (check(TokenType.COMMA)) {
                advance();
                params.add(ident("parameter name"));
            }
        }
        expect(TokenType.RPAREN, "expected ')' after parameters");
        if (params.size() > 8) throw new ScriptException("a function can take at most 8 parameters", line);
        return new FnDecl(name, params, block(), line);
    }

    // ── statements ──────────────────────────────────────────────────────────

    private Block block() {
        int line = peek().line;
        expect(TokenType.LBRACE, "expected '{'");
        skipNewlines();
        List<Stmt> stmts = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            stmts.add(statement());
            skipNewlines();
        }
        expect(TokenType.RBRACE, "expected '}'");
        return new Block(stmts, line);
    }

    private Stmt statement() {
        int line = peek().line;
        switch (peek().type) {
            case LET: {
                advance();
                String name = ident("let name");
                expect(TokenType.ASSIGN, "expected '=' in let");
                Expr value = expression();
                terminator();
                return new LetStmt(name, value, line);
            }
            case IF: return ifStatement();
            case WHILE: {
                advance();
                Expr cond = expression();
                Block body = block();
                return new WhileStmt(cond, body, line);
            }
            case FOR: {
                advance();
                String name = ident("for loop variable");
                expect(TokenType.IN, "expected 'in' in for loop");
                Expr first = expression();
                if (check(TokenType.DOTDOT)) {
                    advance();
                    Expr hi = expression();
                    Block body = block();
                    return new ForStmt(name, first, hi, body, line);
                }
                Block body = block();
                return new ForEachStmt(name, first, body, line);
            }
            case AFTER: {
                advance();
                Expr delay = expression();
                Block body = block();
                return new AfterStmt(delay, body, line);
            }
            case BREAK: advance(); terminator(); return new BreakStmt(line);
            case CONTINUE: advance(); terminator(); return new ContinueStmt(line);
            case RETURN: {
                advance();
                Expr value = null;
                if (!check(TokenType.NEWLINE) && !check(TokenType.RBRACE) && !check(TokenType.EOF)
                        && !startsStatement(peek().type)) {
                    value = expression();
                }
                terminator();
                return new ReturnStmt(value, line);
            }
            default: {
                Expr e = expression();
                if (check(TokenType.ASSIGN)) {
                    advance();
                    Expr value = expression();
                    terminator();
                    if (e instanceof VarRef ref) return new AssignStmt(ref.name, value, line);
                    if (e instanceof IndexGet ig) return new IndexAssignStmt(ig.target, ig.index, value, line);
                    throw new ScriptException("can only assign to a variable or a list element", line);
                }
                terminator();
                return new ExprStmt(e, line);
            }
        }
    }

    private Stmt ifStatement() {
        int line = peek().line;
        expect(TokenType.IF, "expected 'if'");
        Expr cond = expression();
        Block thenB = block();
        Stmt elseBranch = null;
        // allow a newline before 'else' (so "} else {" and "}\n else {" both work)
        int save = idx;
        skipNewlines();
        if (check(TokenType.ELSE)) {
            advance();
            if (check(TokenType.IF)) {
                elseBranch = ifStatement();
            } else {
                elseBranch = block();
            }
        } else {
            idx = save; // restore — those newlines terminate the if-stmt
        }
        return new IfStmt(cond, thenB, elseBranch, line);
    }

    // ── expressions (precedence climbing) ───────────────────────────────────

    private Expr expression() { return orExpr(); }

    private Expr orExpr() {
        Expr left = andExpr();
        while (check(TokenType.OR)) {
            int line = advance().line;
            Expr right = andExpr();
            left = new Logical(false, left, right, line);
        }
        return left;
    }

    private Expr andExpr() {
        Expr left = cmpExpr();
        while (check(TokenType.AND)) {
            int line = advance().line;
            Expr right = cmpExpr();
            left = new Logical(true, left, right, line);
        }
        return left;
    }

    private Expr cmpExpr() {
        Expr left = addExpr();
        while (check(TokenType.EQ) || check(TokenType.NEQ) || check(TokenType.LT)
                || check(TokenType.LTE) || check(TokenType.GT) || check(TokenType.GTE)) {
            Token op = advance();
            Expr right = addExpr();
            left = new Binary(op.text, left, right, op.line);
        }
        return left;
    }

    private Expr addExpr() {
        Expr left = mulExpr();
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            Token op = advance();
            Expr right = mulExpr();
            left = new Binary(op.text, left, right, op.line);
        }
        return left;
    }

    private Expr mulExpr() {
        Expr left = unary();
        while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.PERCENT)) {
            Token op = advance();
            Expr right = unary();
            left = new Binary(op.text, left, right, op.line);
        }
        return left;
    }

    private Expr unary() {
        if (check(TokenType.MINUS)) {
            Token op = advance();
            return new Unary("-", unary(), op.line);
        }
        if (check(TokenType.NOT)) {
            Token op = advance();
            return new Unary("not", unary(), op.line);
        }
        return postfix();
    }

    private Expr postfix() {
        Expr e = primary();
        while (true) {
            if (check(TokenType.DOT)) {
                Token dot = advance();
                Token field = expect(TokenType.IDENT, "expected a field name after '.'");
                e = new FieldGet(e, field.text, dot.line);
            } else if (check(TokenType.LBRACKET)) {
                Token open = advance();
                skipNewlines();
                Expr index = expression();
                skipNewlines();
                expect(TokenType.RBRACKET, "expected ']'");
                e = new IndexGet(e, index, open.line);
            } else {
                return e;
            }
        }
    }

    private Expr primary() {
        Token t = peek();
        switch (t.type) {
            case NUMBER: advance(); return new NumLit(t.num, t.line);
            case STRING: advance(); return new StrLit(t.text, t.line);
            case TRUE:   advance(); return new BoolLit(true, t.line);
            case FALSE:  advance(); return new BoolLit(false, t.line);
            case NULL:   advance(); return new NullLit(t.line);
            case LPAREN: {
                advance();
                Expr e = expression();
                expect(TokenType.RPAREN, "expected ')'");
                return e;
            }
            case LBRACKET: {
                advance();
                List<Expr> items = new ArrayList<>();
                skipNewlines();
                if (!check(TokenType.RBRACKET)) {
                    items.add(expression());
                    skipNewlines();
                    while (check(TokenType.COMMA)) {
                        advance();
                        skipNewlines();
                        if (check(TokenType.RBRACKET)) break; // trailing comma
                        items.add(expression());
                        skipNewlines();
                    }
                }
                expect(TokenType.RBRACKET, "expected ']' to close the list");
                return new ListLit(items, t.line);
            }
            case IDENT: {
                advance();
                if (check(TokenType.LPAREN)) {
                    return call(t);
                }
                return new VarRef(t.text, t.line);
            }
            default:
                throw err("expected an expression, found '" + t.text + "'");
        }
    }

    private Expr call(Token name) {
        expect(TokenType.LPAREN, "expected '('");
        List<Expr> args = new ArrayList<>();
        skipNewlines();
        if (!check(TokenType.RPAREN)) {
            args.add(expression());
            skipNewlines();
            while (check(TokenType.COMMA)) {
                advance();
                skipNewlines();
                args.add(expression());
                skipNewlines();
            }
        }
        expect(TokenType.RPAREN, "expected ')' after call arguments");
        return new Call(name.text, args, name.line);
    }

    // ── token helpers ───────────────────────────────────────────────────────

    private String ident(String what) {
        return expect(TokenType.IDENT, "expected " + what).text;
    }

    private double numberLiteral(String what) {
        boolean neg = false;
        if (check(TokenType.MINUS)) { advance(); neg = true; }
        Token n = expect(TokenType.NUMBER, "expected " + what);
        return neg ? -n.num : n.num;
    }

    private boolean boolLiteral(String what) {
        if (check(TokenType.TRUE)) { advance(); return true; }
        if (check(TokenType.FALSE)) { advance(); return false; }
        throw err("expected " + what + " (true/false)");
    }

    /**
     * A statement terminator: NEWLINE, RBRACE (peeked, not consumed), or EOF. A token that can
     * only begin a new statement also implicitly terminates the current one, so the same-line
     * form {@code { clearAim() return }} works.
     */
    private void terminator() {
        if (check(TokenType.NEWLINE)) { advance(); return; }
        if (check(TokenType.RBRACE) || check(TokenType.EOF)) return;
        if (startsStatement(peek().type)) return;
        throw err("expected end of statement (newline), found '" + peek().text + "'");
    }

    private static boolean startsStatement(TokenType t) {
        switch (t) {
            case LET: case IF: case WHILE: case FOR: case AFTER:
            case BREAK: case CONTINUE: case RETURN:
                return true;
            default:
                return false;
        }
    }

    private void skipNewlines() {
        while (check(TokenType.NEWLINE)) advance();
    }

    private boolean check(TokenType type) { return peek().type == type; }

    private boolean checkWord(String word) {
        return peek().type == TokenType.IDENT && peek().text.equals(word);
    }

    private Token peek() { return tokens.get(idx); }

    private Token advance() {
        Token t = tokens.get(idx);
        if (idx < tokens.size() - 1) idx++;
        return t;
    }

    private Token expect(TokenType type, String message) {
        if (check(type)) return advance();
        throw err(message);
    }

    private ScriptException err(String message) {
        return new ScriptException(message, peek().line);
    }
}
