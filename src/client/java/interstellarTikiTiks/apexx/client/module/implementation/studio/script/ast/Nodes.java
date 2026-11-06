package interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast;

import java.util.List;

/**
 * AST node definitions for the Apexx DSL, all nested under one holder class.
 *
 * <p>Every node records its source {@code line}. Variable / setting references,
 * user functions and host-call names are resolved to integer slots at compile
 * time by the {@code ScriptCompiler}; the parser leaves them as names here.</p>
 */
public final class Nodes {
    private Nodes() {}

    // ── Module ──────────────────────────────────────────────────────────────

    /** Lifecycle handlers plus {@code EVERY} (timer) and {@code EVENT} (game event with an ev record). */
    public enum EventKind { ENABLE, DISABLE, TICK, RENDER, FRAME, TRIGGER, EVERY, EVENT }

    public enum SettingKind { SLIDER, TOGGLE, MODE, COLOR }

    /** One {@code setting ...} declaration. */
    public static final class SettingDecl {
        public final SettingKind kind;
        public final String name;
        public final int line;
        // slider
        public final double defaultNum;
        public final double min, max, step;
        /** Gaussian StDev companion default for a slider ({@code jitter 15}); NaN = none. */
        public double jitter = Double.NaN;
        // toggle
        public final boolean defaultBool;
        /** A toggle that gets its own keybind in the GUI ({@code ... = false bindable}). */
        public boolean bindable;
        // mode
        public final String defaultMode;
        public final List<String> modes;
        // color (0xAARRGGBB; 0xRRGGBB is treated as opaque)
        public final int defaultColor;
        /** Optional {@code "..."} help text after the declaration. */
        public String description;
        /** Optional {@code if <name>} visibility dependency (Setting.setDependsOn syntax). */
        public String dependsOn;

        public static SettingDecl slider(String name, double def, double min, double max, double step, int line) {
            return new SettingDecl(SettingKind.SLIDER, name, line, def, min, max, step, false, null, null, 0);
        }
        public static SettingDecl toggle(String name, boolean def, int line) {
            return new SettingDecl(SettingKind.TOGGLE, name, line, 0, 0, 0, 0, def, null, null, 0);
        }
        public static SettingDecl mode(String name, String def, List<String> modes, int line) {
            return new SettingDecl(SettingKind.MODE, name, line, 0, 0, 0, 0, false, def, modes, 0);
        }
        public static SettingDecl color(String name, int argb, int line) {
            return new SettingDecl(SettingKind.COLOR, name, line, 0, 0, 0, 0, false, null, null, argb);
        }

        private SettingDecl(SettingKind kind, String name, int line, double defaultNum,
                            double min, double max, double step, boolean defaultBool,
                            String defaultMode, List<String> modes, int defaultColor) {
            this.kind = kind;
            this.name = name;
            this.line = line;
            this.defaultNum = defaultNum;
            this.min = min;
            this.max = max;
            this.step = step;
            this.defaultBool = defaultBool;
            this.defaultMode = defaultMode;
            this.modes = modes;
            this.defaultColor = defaultColor;
        }
    }

    /** One {@code var name = expr} module-level declaration. */
    public static final class VarDecl {
        public final String name;
        public final Expr init;
        public final int line;
        public VarDecl(String name, Expr init, int line) {
            this.name = name; this.init = init; this.line = line;
        }
    }

    /**
     * One {@code on <event> { ... }} handler. {@code event} is the catalog name; {@code param}
     * names the local the event record is bound to ({@code on hurt(ev)}), or null.
     */
    public static final class Handler {
        public final EventKind kind;
        public final String event;
        public final String param;
        public final double intervalMs;
        public final Block body;
        public final int line;
        public int paramSlot = -1; // filled by compiler
        public Handler(EventKind kind, String event, String param, double intervalMs, Block body, int line) {
            this.kind = kind; this.event = event; this.param = param; this.intervalMs = intervalMs;
            this.body = body; this.line = line;
        }
        /** Legacy constructor for the four original lifecycle handlers. */
        public Handler(EventKind kind, Block body, int line) {
            this(kind, kind.name().toLowerCase(), null, 0, body, line);
        }
    }

    /** {@code fn name(a, b) { ... return x }} — a user function. */
    public static final class FnDecl {
        public final String name;
        public final List<String> params;
        public final Block body;
        public final int line;
        public int[] paramSlots;  // filled by compiler
        public int maxLocals;     // filled by compiler
        public FnDecl(String name, List<String> params, Block body, int line) {
            this.name = name; this.params = params; this.body = body; this.line = line;
        }
    }

    /** The whole module. */
    public static final class ModuleDecl {
        public final String name;
        public final String category;   // bare identifier, may be null
        public final String description; // may be null
        public final List<SettingDecl> settings;
        public final List<VarDecl> vars;
        public final List<Handler> handlers;
        public final List<FnDecl> functions;
        /** {@code type toggle | oneshot | trigger}; null = toggle. */
        public final String scriptType;
        /** {@code key "R"}: the default keybind (applied only while unbound); null = none. */
        public final String defaultKey;
        public final int line;
        public ModuleDecl(String name, String category, String description,
                          List<SettingDecl> settings, List<VarDecl> vars,
                          List<Handler> handlers, int line) {
            this(name, category, description, settings, vars, handlers, List.of(), null, null, line);
        }
        public ModuleDecl(String name, String category, String description,
                          List<SettingDecl> settings, List<VarDecl> vars,
                          List<Handler> handlers, List<FnDecl> functions,
                          String scriptType, String defaultKey, int line) {
            this.name = name;
            this.category = category;
            this.description = description;
            this.settings = settings;
            this.vars = vars;
            this.handlers = handlers;
            this.functions = functions;
            this.scriptType = scriptType;
            this.defaultKey = defaultKey;
            this.line = line;
        }
    }

    // ── Statements ──────────────────────────────────────────────────────────

    public abstract static class Stmt {
        public final int line;
        /**
         * Debugger table set by the resolver: index = local slot, value = the name visible at this statement
         * (null for a slot that is not in scope here). Arrays are shared between statements in the same scope.
         */
        public String[] debugLocals;
        protected Stmt(int line){this.line=line;}
    }

    public static final class Block extends Stmt {
        public final List<Stmt> statements;
        public Block(List<Stmt> statements, int line) { super(line); this.statements = statements; }
    }

    /** {@code let name = expr} — block-scoped local. Slot resolved at compile time. */
    public static final class LetStmt extends Stmt {
        public final String name;
        public final Expr value;
        public int slot = -1; // filled by compiler
        public LetStmt(String name, Expr value, int line) { super(line); this.name = name; this.value = value; }
    }

    /** {@code name = expr} — assign to an existing var/let. */
    public static final class AssignStmt extends Stmt {
        public final String name;
        public final Expr value;
        public int slot = -1;      // filled by compiler
        public boolean moduleVar;  // true = module-level var, false = local let
        public AssignStmt(String name, Expr value, int line) { super(line); this.name = name; this.value = value; }
    }

    /** {@code target[index] = value} — set a list element. */
    public static final class IndexAssignStmt extends Stmt {
        public final Expr target, index, value;
        public IndexAssignStmt(Expr target, Expr index, Expr value, int line) {
            super(line); this.target = target; this.index = index; this.value = value;
        }
    }

    public static final class IfStmt extends Stmt {
        public final Expr condition;
        public final Block thenBlock;
        public final Stmt elseBranch; // Block or IfStmt or null
        public IfStmt(Expr condition, Block thenBlock, Stmt elseBranch, int line) {
            super(line); this.condition = condition; this.thenBlock = thenBlock; this.elseBranch = elseBranch;
        }
    }

    public static final class WhileStmt extends Stmt {
        public final Expr condition;
        public final Block body;
        public WhileStmt(Expr condition, Block body, int line) { super(line); this.condition = condition; this.body = body; }
    }

    /** {@code for name in lo..hi { ... }} — bounded counted loop. */
    public static final class ForStmt extends Stmt {
        public final String name;
        public final Expr lo, hi;
        public final Block body;
        public int slot = -1; // loop var slot
        public ForStmt(String name, Expr lo, Expr hi, Block body, int line) {
            super(line); this.name = name; this.lo = lo; this.hi = hi; this.body = body;
        }
    }

    /** {@code for name in list { ... }} — iterate a snapshot of a list. */
    public static final class ForEachStmt extends Stmt {
        public final String name;
        public final Expr iterable;
        public final Block body;
        public int slot = -1;
        public ForEachStmt(String name, Expr iterable, Block body, int line) {
            super(line); this.name = name; this.iterable = iterable; this.body = body;
        }
    }

    /** {@code after ms { ... }} — run the block later; it sees a snapshot of the current locals. */
    public static final class AfterStmt extends Stmt {
        public final Expr delayMs;
        public final Block body;
        public AfterStmt(Expr delayMs, Block body, int line) { super(line); this.delayMs = delayMs; this.body = body; }
    }

    public static final class ExprStmt extends Stmt {
        public final Expr expr;
        public ExprStmt(Expr expr, int line) { super(line); this.expr = expr; }
    }

    public static final class BreakStmt extends Stmt { public BreakStmt(int line){super(line);} }
    public static final class ContinueStmt extends Stmt { public ContinueStmt(int line){super(line);} }
    /** {@code return} or {@code return expr} (the value only matters inside a fn). */
    public static final class ReturnStmt extends Stmt {
        public final Expr value; // may be null
        public ReturnStmt(Expr value, int line){ super(line); this.value = value; }
        public ReturnStmt(int line){ this(null, line); }
    }

    // ── Expressions ─────────────────────────────────────────────────────────

    public abstract static class Expr { public final int line; protected Expr(int line){this.line=line;} }

    public static final class NumLit extends Expr {
        public final double value;
        public NumLit(double value, int line) { super(line); this.value = value; }
    }
    public static final class StrLit extends Expr {
        public final String value;
        public StrLit(String value, int line) { super(line); this.value = value; }
    }
    public static final class BoolLit extends Expr {
        public final boolean value;
        public BoolLit(boolean value, int line) { super(line); this.value = value; }
    }
    public static final class NullLit extends Expr { public NullLit(int line){super(line);} }

    /** {@code [a, b, c]} — a new list. */
    public static final class ListLit extends Expr {
        public final List<Expr> items;
        public ListLit(List<Expr> items, int line) { super(line); this.items = items; }
    }

    /**
     * A bare identifier reference, resolved by the compiler to a setting, a module var
     * or a local.
     */
    public static final class VarRef extends Expr {
        public final String name;
        public enum Kind { UNRESOLVED, SETTING, MODULE_VAR, LOCAL }
        public Kind kind = Kind.UNRESOLVED;
        public int slot = -1;
        public VarRef(String name, int line) { super(line); this.name = name; }
    }

    /** {@code target.field} — read a record field or an exposed entity field. */
    public static final class FieldGet extends Expr {
        public final Expr target;
        public final String field;
        public FieldGet(Expr target, String field, int line) { super(line); this.target = target; this.field = field; }
    }

    /** {@code target[index]} — list element or record field by string. */
    public static final class IndexGet extends Expr {
        public final Expr target, index;
        public IndexGet(Expr target, Expr index, int line) { super(line); this.target = target; this.index = index; }
    }

    public static final class Unary extends Expr {
        public final String op; // "-" or "not"
        public final Expr operand;
        public Unary(String op, Expr operand, int line) { super(line); this.op = op; this.operand = operand; }
    }

    public static final class Binary extends Expr {
        public final String op; // + - * / % == != < <= > >=
        public final Expr left, right;
        public Binary(String op, Expr left, Expr right, int line) {
            super(line); this.op = op; this.left = left; this.right = right;
        }
    }

    /** Short-circuit {@code and}/{@code or}. */
    public static final class Logical extends Expr {
        public final boolean isAnd;
        public final Expr left, right;
        public Logical(boolean isAnd, Expr left, Expr right, int line) {
            super(line); this.isAnd = isAnd; this.left = left; this.right = right;
        }
    }

    /** A call: a user {@code fn} ({@code userFn >= 0}) or a host function ({@code fnIndex}). */
    public static final class Call extends Expr {
        public final String name;
        public final List<Expr> args;
        public int fnIndex = -1; // host function index, filled by compiler
        public int userFn = -1;  // user function index, filled by compiler
        public Call(String name, List<Expr> args, int line) {
            super(line); this.name = name; this.args = args;
        }
    }
}
