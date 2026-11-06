package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Apexx tree-walking interpreter. One instance is reused per script.
 *
 * <p>Safety guarantees:</p>
 * <ul>
 *   <li><b>Instruction budget</b> — every statement and expression node costs one unit; at zero the
 *       invocation aborts with {@link BudgetExceeded}, so {@code while true {}} halts.</li>
 *   <li><b>Time budget</b> — an invocation that runs longer than {@link #MAX_NANOS} also aborts, so
 *       expensive host calls in a loop cannot stall a frame.</li>
 *   <li><b>Bounded loops, lists, strings and recursion</b> — for-loops clamp at {@link #MAX_FOR_ITERS},
 *       lists at {@link Value#MAX_LIST}, strings at {@link #MAX_STRING_LEN}, calls at {@link #MAX_DEPTH}.</li>
 *   <li><b>NaN-guarded division</b> — {@code x / 0} and {@code x % 0} yield 0.</li>
 *   <li><b>No reflection</b> — entity fields go through the fixed {@link EntityFields} resolver.</li>
 * </ul>
 */
public final class Evaluator {

    public static final int MAX_FOR_ITERS = 100_000;
    public static final int DEFAULT_BUDGET = 50_000;
    public static final int MAX_STRING_LEN = 64 * 1024;
    public static final int MAX_DEPTH = 32;
    public static final long MAX_NANOS = 8_000_000L;
    private static final int MAX_ARGS = 8;

    /** Thrown internally when the instruction or time budget is exhausted. */
    public static final class BudgetExceeded extends RuntimeException {
        public BudgetExceeded() { super("instruction budget exceeded"); }
        public BudgetExceeded(String why) { super(why); }
        @Override public Throwable fillInStackTrace() { return this; }
    }

    /** A runtime fault inside a script, with the line it happened on (surfaced in the IDE). */
    public static final class ScriptRuntimeError extends RuntimeException {
        public final int line;
        public ScriptRuntimeError(String message, int line) {
            super(line > 0 ? "line " + line + ": " + message : message);
            this.line = line;
        }
        @Override public Throwable fillInStackTrace() { return this; }
    }

    /** Reads the fixed field set of an opaque entity ({@code e.health}). Installed by {@link HostApi}. */
    @FunctionalInterface
    public interface EntityFields {
        Value get(Object entity, String field);
    }

    private static volatile EntityFields entityFields = (e, f) -> Value.NULL;

    public static void setEntityFields(EntityFields resolver) {
        entityFields = resolver == null ? (e, f) -> Value.NULL : resolver;
    }

    private static final class BreakSignal extends RuntimeException {
        @Override public Throwable fillInStackTrace() { return this; }
    }
    private static final class ContinueSignal extends RuntimeException {
        @Override public Throwable fillInStackTrace() { return this; }
    }
    private static final class ReturnSignal extends RuntimeException {
        @Override public Throwable fillInStackTrace() { return this; }
    }
    private static final BreakSignal BREAK = new BreakSignal();
    private static final ContinueSignal CONTINUE = new ContinueSignal();
    private static final ReturnSignal RETURN = new ReturnSignal();

    private final ScriptHost host;
    private final HostFn[] hostTable;
    private final FnDecl[] functions;
    final Value[] moduleVars;
    private final int maxLocals;
    /** The current local frame (handler, scheduled block, or user function). */
    private Value[] locals;

    private int budget;
    private int depth;
    private long startNanos;
    private Value returnValue = Value.NULL;
    /** The line of the statement being executed, for runtime error reports. */
    private int currentLine;

    // ── snapshot debugger (see ScriptDebugger); all null/zero unless a breakpoint session is active ──
    private ScriptDebugger.Session debugSession;
    private String debugEvent;
    private int debugHits;
    private String[] settingNames = new String[0];
    private String[] varNames = new String[0];

    /** Name tables for debugger snapshots (setting slot -> name, module var slot -> name). */
    public void setDebugNames(String[] settingNames, String[] varNames) {
        this.settingNames = settingNames == null ? new String[0] : settingNames;
        this.varNames = varNames == null ? new String[0] : varNames;
    }

    /** Sets the breakpoint session and event name for the next handler run(s); null session = no debugging. */
    public void setDebugContext(ScriptDebugger.Session session, String event) {
        this.debugSession = session;
        this.debugEvent = event;
    }

    public ScriptDebugger.Session debugSession() { return debugSession; }

    public String debugEvent() { return debugEvent; }

    public Evaluator(ScriptHost host, HostFn[] hostTable, FnDecl[] functions, int moduleVarCount, int maxLocals) {
        this.host = host;
        this.hostTable = hostTable;
        this.functions = functions == null ? new FnDecl[0] : functions;
        this.moduleVars = new Value[Math.max(0, moduleVarCount)];
        this.maxLocals = Math.max(0, maxLocals);
        this.locals = new Value[this.maxLocals];
        for (int i = 0; i < moduleVars.length; i++) moduleVars[i] = Value.number(0);
        for (int i = 0; i < locals.length; i++) locals[i] = Value.NULL;
    }

    public Value getModuleVar(int slot) { return moduleVars[slot]; }
    public void setModuleVar(int slot, Value v) { moduleVars[slot] = v; }

    /** Runs a handler with no event value. */
    public boolean runHandler(Block block) {
        return runHandler(block, null, -1);
    }

    /**
     * Runs one handler with a fresh budget, binding {@code eventValue} to {@code paramSlot} when set.
     *
     * @return true if the handler completed (or returned) cleanly; false if a budget aborted it.
     * @throws ScriptRuntimeError for a script fault (bad call arity, deep recursion, ...)
     */
    public boolean runHandler(Block block, Value eventValue, int paramSlot) {
        Value[] frame = new Value[maxLocals];
        for (int i = 0; i < frame.length; i++) frame[i] = Value.NULL;
        if (paramSlot >= 0 && paramSlot < frame.length) frame[paramSlot] = eventValue == null ? Value.NULL : eventValue;
        return runFrame(block, frame);
    }

    /** Runs an {@code after} block with the local frame captured when it was scheduled. */
    public boolean runScheduled(Block block, Value[] capturedLocals) {
        return runFrame(block, capturedLocals);
    }

    /** Evaluates one standalone expression (a {@code var} initializer) with a fresh budget and empty frame. */
    public Value evalExpression(Expr expr) {
        this.budget = DEFAULT_BUDGET;
        this.depth = 0;
        this.startNanos = System.nanoTime();
        Value[] saved = this.locals;
        int savedLine = this.currentLine;
        Value[] frame = new Value[Math.max(1, maxLocals)];
        for (int i = 0; i < frame.length; i++) frame[i] = Value.NULL;
        this.locals = frame;
        try {
            return eval(expr);
        } finally {
            this.locals = saved;
            this.currentLine = savedLine;
        }
    }

    private boolean runFrame(Block block, Value[] frame) {
        this.budget = DEFAULT_BUDGET;
        this.depth = 0;
        this.startNanos = System.nanoTime();
        Value[] saved = this.locals;
        int savedLine = this.currentLine;
        int savedHits = this.debugHits;
        this.locals = frame;
        this.currentLine = 0;
        this.debugHits = 0;
        try {
            execBlock(block);
            return true;
        } catch (ReturnSignal | BreakSignal | ContinueSignal r) {
            // `return` ends the handler; a stray break/continue outside a loop is a harmless no-op.
            return true;
        } catch (BudgetExceeded b) {
            return false;
        } catch (StackOverflowError so) {
            throw new ScriptRuntimeError("script nested too deeply", currentLine);
        } finally {
            this.locals = saved;
            this.currentLine = savedLine;
            this.debugHits = savedHits;
        }
    }

    /**
     * Records a debugger snapshot for {@code stmt}. Bounded: at most {@link ScriptDebugger#PER_RUN_CAP} per
     * handler run, {@link ScriptDebugger#MAX_VARS} entries per section, short formatted values. Never throws.
     */
    private void debugCapture(Stmt stmt) {
        ScriptDebugger.Session session = debugSession;
        if (session == null || debugHits >= ScriptDebugger.PER_RUN_CAP) return;
        debugHits++;
        try {
            List<ScriptDebugger.Var> loc = new ArrayList<>();
            String[] names = stmt.debugLocals;
            if (names != null) {
                for (int i = 0; i < names.length && i < locals.length && loc.size() < ScriptDebugger.MAX_VARS; i++) {
                    if (names[i] != null) loc.add(new ScriptDebugger.Var(names[i], ScriptDebugger.format(locals[i])));
                }
            }
            List<ScriptDebugger.Var> vars = new ArrayList<>();
            for (int i = 0; i < moduleVars.length && vars.size() < ScriptDebugger.MAX_VARS; i++) {
                String n = i < varNames.length && varNames[i] != null ? varNames[i] : "var" + i;
                vars.add(new ScriptDebugger.Var(n, ScriptDebugger.format(moduleVars[i])));
            }
            List<ScriptDebugger.Var> sets = new ArrayList<>();
            for (int i = 0; i < settingNames.length && sets.size() < ScriptDebugger.MAX_VARS; i++) {
                Value v;
                try {
                    v = host.readSetting(i);
                } catch (Throwable t) {
                    v = Value.NULL;
                }
                sets.add(new ScriptDebugger.Var(settingNames[i], ScriptDebugger.format(v)));
            }
            ScriptDebugger.record(session.script, debugEvent, stmt.line, List.copyOf(loc), List.copyOf(vars), List.copyOf(sets));
        } catch (Throwable ignored) {
            // The debugger must never change what the script does.
        }
    }

    // ── statements ──────────────────────────────────────────────────────────

    private void execBlock(Block block) {
        List<Stmt> stmts = block.statements;
        for (int i = 0; i < stmts.size(); i++) {
            exec(stmts.get(i));
        }
    }

    private void exec(Stmt stmt) {
        tick();
        int previousLine = currentLine;
        currentLine = stmt.line;
        // One snapshot per arrival at a breakpoint line (not one per statement sharing that line).
        if (debugSession != null && previousLine != stmt.line && !(stmt instanceof Block)
                && debugSession.isBreak(stmt.line)) {
            debugCapture(stmt);
        }
        if (stmt instanceof ExprStmt s) {
            eval(s.expr);
        } else if (stmt instanceof LetStmt s) {
            locals[s.slot] = eval(s.value);
        } else if (stmt instanceof AssignStmt s) {
            Value v = eval(s.value);
            if (s.moduleVar) moduleVars[s.slot] = v;
            else locals[s.slot] = v;
        } else if (stmt instanceof IndexAssignStmt s) {
            execIndexAssign(s);
        } else if (stmt instanceof IfStmt s) {
            if (eval(s.condition).asBool()) {
                execBlock(s.thenBlock);
            } else if (s.elseBranch instanceof Block b) {
                execBlock(b);
            } else if (s.elseBranch instanceof IfStmt elif) {
                exec(elif);
            }
        } else if (stmt instanceof WhileStmt s) {
            execWhile(s);
        } else if (stmt instanceof ForStmt s) {
            execFor(s);
        } else if (stmt instanceof ForEachStmt s) {
            execForEach(s);
        } else if (stmt instanceof AfterStmt s) {
            double ms = eval(s.delayMs).asNumber();
            if (!Double.isFinite(ms) || ms < 0) ms = 0;
            host.schedule(s.body, locals.clone(), Math.min(ms, 600_000));
        } else if (stmt instanceof Block b) {
            execBlock(b);
        } else if (stmt instanceof BreakStmt) {
            throw BREAK;
        } else if (stmt instanceof ContinueStmt) {
            throw CONTINUE;
        } else if (stmt instanceof ReturnStmt r) {
            returnValue = r.value == null ? Value.NULL : eval(r.value);
            throw RETURN;
        }
    }

    private void execIndexAssign(IndexAssignStmt s) {
        Value target = eval(s.target);
        Value index = eval(s.index);
        Value value = eval(s.value);
        if (target.type != Value.Type.LIST) {
            throw new ScriptRuntimeError("only list elements can be assigned with [ ]", s.line);
        }
        List<Value> list = target.asList();
        int i = (int) Math.floor(index.asNumber());
        if (i < 0) i += list.size();
        if (i >= 0 && i < list.size()) list.set(i, value);
        else if (i == list.size() && list.size() < Value.MAX_LIST) list.add(value);
    }

    private void execWhile(WhileStmt s) {
        while (eval(s.condition).asBool()) {
            tick();
            currentLine = s.line; // each iteration re-arrives at the body (debugger)
            try {
                execBlock(s.body);
            } catch (BreakSignal b) {
                break;
            } catch (ContinueSignal c) {
                // continue
            }
        }
    }

    private void execFor(ForStmt s) {
        double loD = eval(s.lo).asNumber();
        double hiD = eval(s.hi).asNumber();
        if (Double.isNaN(loD) || Double.isNaN(hiD)) return;
        long lo = (long) Math.floor(loD);
        long hi = (long) Math.floor(hiD);
        if (hi <= lo) return;
        long count = Math.min(hi - lo, MAX_FOR_ITERS);
        for (long k = 0; k < count; k++) {
            tick();
            currentLine = s.line;
            locals[s.slot] = Value.number(lo + k);
            try {
                execBlock(s.body);
            } catch (BreakSignal b) {
                break;
            } catch (ContinueSignal c) {
                // continue
            }
        }
    }

    private void execForEach(ForEachStmt s) {
        Value it = eval(s.iterable);
        List<Value> items;
        if (it.type == Value.Type.LIST) {
            items = new ArrayList<>(it.asList()); // snapshot: the body may mutate the list
        } else if (it.type == Value.Type.RECORD) {
            items = new ArrayList<>();
            for (String k : it.asRecord().keySet()) items.add(Value.string(k));
        } else if (it.type == Value.Type.STRING) {
            items = new ArrayList<>();
            String str = it.asString();
            for (int i = 0; i < str.length() && i < MAX_FOR_ITERS; i++) items.add(Value.string(String.valueOf(str.charAt(i))));
        } else if (it.type == Value.Type.NULL) {
            return;
        } else {
            throw new ScriptRuntimeError("'for " + s.name + " in ...' needs a list, record or string", s.line);
        }
        for (Value v : items) {
            tick();
            currentLine = s.line;
            locals[s.slot] = v;
            try {
                execBlock(s.body);
            } catch (BreakSignal b) {
                break;
            } catch (ContinueSignal c) {
                // continue
            }
        }
    }

    // ── expressions ─────────────────────────────────────────────────────────

    private Value eval(Expr expr) {
        tick();
        if (expr instanceof NumLit e) return Value.number(e.value);
        if (expr instanceof StrLit e) return Value.string(e.value);
        if (expr instanceof BoolLit e) return Value.bool(e.value);
        if (expr instanceof NullLit) return Value.NULL;
        if (expr instanceof VarRef e) return evalVarRef(e);
        if (expr instanceof Unary e) return evalUnary(e);
        if (expr instanceof Binary e) return evalBinary(e);
        if (expr instanceof Logical e) return evalLogical(e);
        if (expr instanceof Call e) return evalCall(e);
        if (expr instanceof FieldGet e) return evalField(eval(e.target), e.field);
        if (expr instanceof IndexGet e) return evalIndex(eval(e.target), eval(e.index));
        if (expr instanceof ListLit e) {
            List<Value> items = new ArrayList<>(e.items.size());
            for (Expr item : e.items) items.add(eval(item));
            return Value.list(items);
        }
        return Value.NULL;
    }

    private Value evalVarRef(VarRef e) {
        switch (e.kind) {
            case SETTING: return host.readSetting(e.slot);
            case MODULE_VAR: return moduleVars[e.slot];
            case LOCAL: return locals[e.slot];
            default: return Value.NULL;
        }
    }

    /** {@code value.field}: a record field, an exposed entity field, or a list/string length. */
    static Value evalField(Value target, String field) {
        switch (target.type) {
            case RECORD: {
                Value v = target.asRecord().get(field);
                return v == null ? Value.NULL : v;
            }
            case ENTITY: {
                Value v = entityFields.get(target.asEntity(), field);
                return v == null ? Value.NULL : v;
            }
            case LIST:
                return (field.equals("length") || field.equals("size")) ? Value.number(target.asList().size()) : Value.NULL;
            case STRING:
                return field.equals("length") ? Value.number(target.asString().length()) : Value.NULL;
            default:
                return Value.NULL;
        }
    }

    static Value evalIndex(Value target, Value index) {
        switch (target.type) {
            case LIST: {
                List<Value> list = target.asList();
                int i = (int) Math.floor(index.asNumber());
                if (i < 0) i += list.size();
                return (i >= 0 && i < list.size()) ? list.get(i) : Value.NULL;
            }
            case RECORD:
                return evalField(target, index.asString());
            case ENTITY:
                return evalField(target, index.asString());
            case STRING: {
                String s = target.asString();
                int i = (int) Math.floor(index.asNumber());
                if (i < 0) i += s.length();
                return (i >= 0 && i < s.length()) ? Value.string(String.valueOf(s.charAt(i))) : Value.NULL;
            }
            default:
                return Value.NULL;
        }
    }

    private Value evalUnary(Unary e) {
        Value v = eval(e.operand);
        if (e.op.equals("-")) return Value.number(-v.asNumber());
        return Value.bool(!v.asBool());
    }

    private Value evalLogical(Logical e) {
        Value l = eval(e.left);
        if (e.isAnd) {
            if (!l.asBool()) return Value.FALSE;
            return Value.bool(eval(e.right).asBool());
        } else {
            if (l.asBool()) return Value.TRUE;
            return Value.bool(eval(e.right).asBool());
        }
    }

    private Value evalBinary(Binary e) {
        Value l = eval(e.left);
        Value r = eval(e.right);
        switch (e.op) {
            case "+":
                if (l.type == Value.Type.LIST && r.type == Value.Type.LIST) {
                    if ((long) l.asList().size() + r.asList().size() > Value.MAX_LIST) throw new BudgetExceeded("list too long");
                    List<Value> joined = new ArrayList<>(l.asList());
                    joined.addAll(r.asList());
                    return Value.list(joined);
                }
                if (l.type == Value.Type.STRING || r.type == Value.Type.STRING) {
                    String ls = l.asString();
                    String rs = r.asString();
                    // Cap the produced length so `while true { s = s + s }` aborts instead of
                    // doubling toward OOM before the instruction budget fires.
                    if ((long) ls.length() + (long) rs.length() > MAX_STRING_LEN) {
                        throw new BudgetExceeded("string too long");
                    }
                    return Value.string(ls + rs);
                }
                return Value.number(l.asNumber() + r.asNumber());
            case "-": return Value.number(l.asNumber() - r.asNumber());
            case "*": return Value.number(l.asNumber() * r.asNumber());
            case "/": {
                double d = r.asNumber();
                if (d == 0.0) return Value.number(0);
                return Value.number(l.asNumber() / d);
            }
            case "%": {
                double d = r.asNumber();
                if (d == 0.0) return Value.number(0);
                return Value.number(l.asNumber() % d);
            }
            case "==": return Value.bool(equalsValue(l, r));
            case "!=": return Value.bool(!equalsValue(l, r));
            case "<": return Value.bool(compare(l, r) < 0);
            case "<=": return Value.bool(compare(l, r) <= 0);
            case ">": return Value.bool(compare(l, r) > 0);
            case ">=": return Value.bool(compare(l, r) >= 0);
            default: return Value.NULL;
        }
    }

    /** Strings compare alphabetically, everything else numerically. */
    private static int compare(Value l, Value r) {
        if (l.type == Value.Type.STRING && r.type == Value.Type.STRING) {
            return l.asString().compareTo(r.asString());
        }
        return Double.compare(l.asNumber(), r.asNumber());
    }

    public static boolean equalsValue(Value a, Value b) {
        if (a.type == Value.Type.NULL || b.type == Value.Type.NULL) {
            return a.type == Value.Type.NULL && b.type == Value.Type.NULL;
        }
        if (a.type == Value.Type.STRING || b.type == Value.Type.STRING) {
            if (a.type == Value.Type.STRING && b.type == Value.Type.STRING) {
                return a.asString().equals(b.asString());
            }
            return false;
        }
        if (a.type == Value.Type.ENTITY || b.type == Value.Type.ENTITY) {
            return a.asEntity() == b.asEntity();
        }
        if (a.type == Value.Type.LIST || b.type == Value.Type.LIST
                || a.type == Value.Type.RECORD || b.type == Value.Type.RECORD) {
            return a.obj == b.obj;
        }
        return a.asNumber() == b.asNumber();
    }

    private Value evalCall(Call e) {
        int n = e.args.size();
        if (n > MAX_ARGS) {
            throw new ScriptRuntimeError("call has " + n + " arguments (max " + MAX_ARGS + ")", e.line);
        }
        Value[] args = new Value[n];
        for (int i = 0; i < n; i++) {
            args[i] = eval(e.args.get(i));
        }
        if (e.userFn >= 0) {
            return callUser(functions[e.userFn], args, e.line);
        }
        Value result = hostTable[e.fnIndex].call(args, host.module());
        return result == null ? Value.NULL : result;
    }

    private Value callUser(FnDecl fn, Value[] args, int line) {
        if (depth >= MAX_DEPTH) {
            throw new ScriptRuntimeError("function '" + fn.name + "' recursed more than " + MAX_DEPTH + " levels", line);
        }
        Value[] frame = new Value[Math.max(1, fn.maxLocals)];
        for (int i = 0; i < frame.length; i++) frame[i] = Value.NULL;
        for (int i = 0; i < fn.paramSlots.length; i++) {
            frame[fn.paramSlots[i]] = i < args.length ? args[i] : Value.NULL;
        }
        Value[] saved = locals;
        int savedLine = currentLine;
        locals = frame;
        depth++;
        returnValue = Value.NULL;
        try {
            execBlock(fn.body);
            return Value.NULL;
        } catch (ReturnSignal r) {
            Value v = returnValue;
            returnValue = Value.NULL;
            return v;
        } catch (BreakSignal | ContinueSignal stray) {
            return Value.NULL;
        } finally {
            depth--;
            locals = saved;
            currentLine = savedLine;
        }
    }

    /** Field names of a record value, for host helpers. */
    static List<String> keys(Value record) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Value> e : record.asRecord().entrySet()) out.add(e.getKey());
        return out;
    }

    // ── budget ──────────────────────────────────────────────────────────────

    private void tick() {
        if (--budget <= 0) throw new BudgetExceeded();
        if ((budget & 0xFF) == 0 && System.nanoTime() - startNanos > MAX_NANOS) {
            throw new BudgetExceeded("time budget exceeded");
        }
    }
}
