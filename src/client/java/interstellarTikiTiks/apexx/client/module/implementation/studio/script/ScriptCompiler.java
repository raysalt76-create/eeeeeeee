package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a parsed {@link ModuleDecl} into a runnable {@link ScriptModule}:
 *
 * <ol>
 *   <li>Builds real Setting objects (auto-GUI, config persistence) and gives each a slot.</li>
 *   <li>Assigns each module {@code var} a slot.</li>
 *   <li>Resolves every name to a setting / module var / local slot, every call to a user
 *       {@code fn} or a host function index (unknown name = compile error), and binds each
 *       handler's event variable and each function's parameters to local slots.</li>
 * </ol>
 *
 * <p>After this pass, runtime dispatch is pure array indexing — no string lookups, no reflection.</p>
 */
public final class ScriptCompiler {

    private ScriptCompiler() {}

    public static ScriptModule compile(String source) {
        ModuleDecl decl = Parser.parse(source);
        String hash = Integer.toHexString(source == null ? 0 : source.hashCode());
        return compile(decl, hash);
    }

    /**
     * Lex + parse + fully resolve a script WITHOUT constructing the {@link ScriptModule} (which needs
     * the Minecraft runtime). Used by the IDE checker and by unit tests, which run the result through
     * an {@link Evaluator} with a mock host.
     */
    public static Resolved parseAndResolve(String source) {
        return resolve(Parser.parse(source));
    }

    /** A resolved AST plus the sizes an {@link Evaluator} needs. */
    public static final class Resolved {
        public final ModuleDecl decl;
        public final int moduleVarCount;
        public final int maxLocals;
        public final FnDecl[] functions;
        public final Map<String, Integer> settingSlots;
        Resolved(ModuleDecl decl, int moduleVarCount, int maxLocals, FnDecl[] functions, Map<String, Integer> settingSlots) {
            this.decl = decl;
            this.moduleVarCount = moduleVarCount;
            this.maxLocals = maxLocals;
            this.functions = functions;
            this.settingSlots = settingSlots;
            this.settingNames = new String[decl.settings.size()];
            for (int i = 0; i < settingNames.length; i++) settingNames[i] = decl.settings.get(i).name;
            this.varNames = new String[decl.vars.size()];
            for (int i = 0; i < varNames.length; i++) varNames[i] = decl.vars.get(i).name;
        }

        /** Setting name per setting slot, for the debugger. */
        public final String[] settingNames;
        /** Module var name per var slot, for the debugger. */
        public final String[] varNames;

        /** An evaluator for this script with the debugger's name tables attached. */
        public Evaluator newEvaluator(ScriptHost host, HostFn[] hostTable) {
            Evaluator ev = new Evaluator(host, hostTable, functions, moduleVarCount, maxLocals);
            ev.setDebugNames(settingNames, varNames);
            return ev;
        }

        /** The first handler for {@code event}, or null (test helper). */
        public Handler handler(String event) {
            for (Handler h : decl.handlers) if (h.event.equals(event)) return h;
            return null;
        }
    }

    static Resolved resolve(ModuleDecl decl) {
        if (decl.name == null || decl.name.isBlank()) {
            throw new ScriptException("module must have a non-empty name", decl.line);
        }
        Map<String, Integer> settingSlots = new HashMap<>();
        for (SettingDecl sd : decl.settings) {
            if (settingSlots.containsKey(sd.name)) {
                throw new ScriptException("duplicate setting '" + sd.name + "'", sd.line);
            }
            settingSlots.put(sd.name, settingSlots.size());
        }
        Map<String, Integer> varSlots = new HashMap<>();
        for (VarDecl vd : decl.vars) {
            if (settingSlots.containsKey(vd.name)) {
                throw new ScriptException("var '" + vd.name + "' collides with a setting name", vd.line);
            }
            if (varSlots.containsKey(vd.name)) {
                throw new ScriptException("duplicate var '" + vd.name + "'", vd.line);
            }
            varSlots.put(vd.name, varSlots.size());
        }
        Map<String, Integer> fnIndex = new HashMap<>();
        for (FnDecl fd : decl.functions) {
            if (fnIndex.containsKey(fd.name)) {
                throw new ScriptException("duplicate function '" + fd.name + "'", fd.line);
            }
            if (HostApi.isHostFunction(fd.name)) {
                throw new ScriptException("'" + fd.name + "' is a built-in function; pick another name", fd.line);
            }
            fnIndex.put(fd.name, fnIndex.size());
        }

        String type = decl.scriptType == null ? "toggle" : decl.scriptType;
        for (Handler h : decl.handlers) {
            if (h.kind == EventKind.TRIGGER && type.equals("toggle")) {
                throw new ScriptException("'on trigger' needs 'type oneshot' or 'type trigger' in the module header", h.line);
            }
        }

        // Handlers share one local frame size; each function sizes its own.
        Resolver handlers = new Resolver(settingSlots, varSlots, decl.functions, fnIndex);
        for (Handler h : decl.handlers) handlers.resolveHandler(h);
        for (VarDecl vd : decl.vars) handlers.resolveExpr(vd.init);

        for (FnDecl fd : decl.functions) {
            Resolver fr = new Resolver(settingSlots, varSlots, decl.functions, fnIndex);
            fr.resolveFunction(fd);
            fd.maxLocals = fr.maxLocals;
        }
        return new Resolved(decl, varSlots.size(), handlers.maxLocals,
                decl.functions.toArray(new FnDecl[0]), settingSlots);
    }

    public static ScriptModule compile(ModuleDecl decl, String sourceHash) {
        Resolved r = resolve(decl);
        List<Setting> settingList = new ArrayList<>();
        for (SettingDecl sd : decl.settings) settingList.add(buildSetting(sd));
        Category category = mapCategory(decl.category);
        String desc = decl.description == null ? "" : decl.description;
        ScriptModule module = new ScriptModule(decl, desc, category,
                settingList.toArray(new Setting[0]), sourceHash);
        module.attachRuntime(r, decl.vars.toArray(new VarDecl[0]));
        return module;
    }

    private static Setting buildSetting(SettingDecl sd) {
        Setting s;
        switch (sd.kind) {
            case SLIDER: {
                SliderSetting slider = new SliderSetting(sd.name, sd.defaultNum, sd.min, sd.max, decimalsForStep(sd.step));
                if (!Double.isNaN(sd.jitter)) slider.withJitter(sd.jitter);
                s = slider;
                break;
            }
            case TOGGLE: {
                BooleanSetting toggle = new BooleanSetting(sd.name, sd.defaultBool);
                if (sd.bindable) toggle.setBindable(true);
                s = toggle;
                break;
            }
            case MODE: {
                ModeSetting m = new ModeSetting(sd.name, sd.modes.toArray(new String[0]));
                m.setCurrentMode(sd.defaultMode);
                s = m;
                break;
            }
            case COLOR:
                s = new ColorSetting(sd.name, new java.awt.Color(sd.defaultColor, true));
                break;
            default:
                throw new ScriptException("unknown setting kind", sd.line);
        }
        if (sd.description != null && !sd.description.isBlank()) s.setDescription(sd.description);
        if (sd.dependsOn != null && !sd.dependsOn.isBlank()) s.setDependsOn(sd.dependsOn);
        return s;
    }

    /** Pick a display precision from the slider step (e.g. step 0.1 → 1 decimal). */
    private static int decimalsForStep(double step) {
        if (step >= 1.0 || step <= 0.0 || !Double.isFinite(step)) return 0;
        // Smallest precision at which the declared step is a whole multiple of the slider granularity.
        double scale = 1.0;
        for (int d = 1; d <= 3; d++) {
            scale *= 10.0;
            double scaled = step * scale;
            if (Math.abs(scaled - Math.rint(scaled)) < 1e-6) return d;
        }
        return 3;
    }

    private static Category mapCategory(String cat) {
        if (cat == null) return Category.PLAYER;
        switch (cat.toLowerCase()) {
            case "combat": return Category.COMBAT;
            case "movement": return Category.MOVEMENT;
            case "automations": return Category.AUTOMATIONS;
            case "render": return Category.RENDER;
            case "player": return Category.PLAYER;
            // A script may not claim the built-in Blatant category (it carries the wire-ray exemption).
            case "blatant": return Category.COMBAT;
            case "smp": return Category.SMP;
            case "client": return Category.CLIENT;
            default: return Category.PLAYER;
        }
    }

    // ── resolver ─────────────────────────────────────────────────────────────

    /**
     * Resolution of locals into flat slots, plus references and calls. A {@code let}/{@code for}
     * var is visible until its enclosing block closes. {@link #maxLocals} is the high-water mark,
     * which sizes the local frame.
     */
    private static final class Resolver {
        private final Map<String, Integer> settingSlots;
        private final Map<String, Integer> varSlots;
        private final List<FnDecl> functions;
        private final Map<String, Integer> fnIndex;

        private final Deque<Map<String, Integer>> scopes = new ArrayDeque<>();
        private int nextLocal = 0;
        int maxLocals = 0;
        // Debugger name tables: bumped whenever the visible locals change, so statements in one
        // scope share a single array.
        private int scopeVersion = 0;
        private int cachedVersion = -1;
        private String[] cachedNames;

        private void pushScope() { scopes.push(new HashMap<>()); scopeVersion++; }
        private void popScope() { scopes.pop(); scopeVersion++; }

        /** slot -> name of every local visible right now (the innermost binding of a name wins). */
        private String[] visibleNames() {
            if (cachedVersion == scopeVersion && cachedNames != null) return cachedNames;
            String[] names = new String[Math.max(0, nextLocal)];
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Map<String, Integer> scope : scopes) { // ArrayDeque iterates innermost first
                for (Map.Entry<String, Integer> e : scope.entrySet()) {
                    int slot = e.getValue();
                    if (seen.add(e.getKey()) && slot >= 0 && slot < names.length) names[slot] = e.getKey();
                }
            }
            cachedNames = names;
            cachedVersion = scopeVersion;
            return names;
        }

        Resolver(Map<String, Integer> settingSlots, Map<String, Integer> varSlots,
                 List<FnDecl> functions, Map<String, Integer> fnIndex) {
            this.settingSlots = settingSlots;
            this.varSlots = varSlots;
            this.functions = functions;
            this.fnIndex = fnIndex;
        }

        void resolveHandler(Handler h) {
            nextLocal = 0;
            scopes.clear();
            pushScope();
            if (h.param != null) h.paramSlot = declareLocal(h.param, h.line);
            resolveBlock(h.body);
            popScope();
        }

        void resolveFunction(FnDecl fd) {
            nextLocal = 0;
            scopes.clear();
            pushScope();
            fd.paramSlots = new int[fd.params.size()];
            for (int i = 0; i < fd.params.size(); i++) {
                String p = fd.params.get(i);
                if (scopes.peek().containsKey(p)) throw new ScriptException("duplicate parameter '" + p + "'", fd.line);
                fd.paramSlots[i] = declareLocal(p, fd.line);
            }
            resolveBlock(fd.body);
            popScope();
        }

        private void resolveBlock(Block block) {
            pushScope();
            int savedNext = nextLocal;
            for (Stmt s : block.statements) resolveStmt(s);
            popScope();
            nextLocal = savedNext; // reclaim slots when the block closes
        }

        private void resolveStmt(Stmt stmt) {
            stmt.debugLocals = visibleNames();
            if (stmt instanceof LetStmt s) {
                resolveExpr(s.value);
                s.slot = declareLocal(s.name, s.line);
            } else if (stmt instanceof AssignStmt s) {
                resolveExpr(s.value);
                if (settingSlots.containsKey(s.name) && lookupLocal(s.name) == null) {
                    throw new ScriptException("cannot assign to setting '" + s.name + "' (settings are read-only)", s.line);
                }
                Integer local = lookupLocal(s.name);
                if (local != null) {
                    s.moduleVar = false;
                    s.slot = local;
                } else if (varSlots.containsKey(s.name)) {
                    s.moduleVar = true;
                    s.slot = varSlots.get(s.name);
                } else {
                    throw new ScriptException("assignment to undeclared name '" + s.name + "' (use 'let' or 'var')", s.line);
                }
            } else if (stmt instanceof IndexAssignStmt s) {
                resolveExpr(s.target);
                resolveExpr(s.index);
                resolveExpr(s.value);
            } else if (stmt instanceof IfStmt s) {
                resolveExpr(s.condition);
                resolveBlock(s.thenBlock);
                if (s.elseBranch instanceof Block b) resolveBlock(b);
                else if (s.elseBranch instanceof IfStmt elif) resolveStmt(elif);
            } else if (stmt instanceof WhileStmt s) {
                resolveExpr(s.condition);
                resolveBlock(s.body);
            } else if (stmt instanceof ForStmt s) {
                resolveExpr(s.lo);
                resolveExpr(s.hi);
                pushScope();
                int savedNext = nextLocal;
                s.slot = declareLocal(s.name, s.line);
                resolveBlock(s.body);
                popScope();
                nextLocal = savedNext;
            } else if (stmt instanceof ForEachStmt s) {
                resolveExpr(s.iterable);
                pushScope();
                int savedNext = nextLocal;
                s.slot = declareLocal(s.name, s.line);
                resolveBlock(s.body);
                popScope();
                nextLocal = savedNext;
            } else if (stmt instanceof AfterStmt s) {
                resolveExpr(s.delayMs);
                // The body runs later on a copy of this frame, so its own lets must not reuse
                // slots that are still live here: keep the slot counter where it is.
                resolveBlock(s.body);
            } else if (stmt instanceof ExprStmt s) {
                resolveExpr(s.expr);
            } else if (stmt instanceof ReturnStmt s) {
                if (s.value != null) resolveExpr(s.value);
            } else if (stmt instanceof Block b) {
                resolveBlock(b);
            }
        }

        void resolveExpr(Expr expr) {
            if (expr instanceof VarRef e) {
                resolveVarRef(e);
            } else if (expr instanceof Unary e) {
                resolveExpr(e.operand);
            } else if (expr instanceof Binary e) {
                resolveExpr(e.left);
                resolveExpr(e.right);
            } else if (expr instanceof Logical e) {
                resolveExpr(e.left);
                resolveExpr(e.right);
            } else if (expr instanceof FieldGet e) {
                resolveExpr(e.target);
            } else if (expr instanceof IndexGet e) {
                resolveExpr(e.target);
                resolveExpr(e.index);
            } else if (expr instanceof ListLit e) {
                for (Expr item : e.items) resolveExpr(item);
            } else if (expr instanceof Call e) {
                Integer user = fnIndex.get(e.name);
                if (user != null) {
                    FnDecl fd = functions.get(user);
                    if (e.args.size() != fd.params.size()) {
                        throw new ScriptException("'" + e.name + "' takes " + fd.params.size()
                                + " argument(s), got " + e.args.size(), e.line);
                    }
                    e.userFn = user;
                } else {
                    int idx = HostApi.indexOf(e.name);
                    if (idx < 0) {
                        throw new ScriptException("unknown function '" + e.name + "'", e.line);
                    }
                    e.fnIndex = idx;
                }
                for (Expr arg : e.args) resolveExpr(arg);
            }
        }

        private void resolveVarRef(VarRef e) {
            Integer local = lookupLocal(e.name);
            if (local != null) {
                e.kind = VarRef.Kind.LOCAL;
                e.slot = local;
                return;
            }
            if (settingSlots.containsKey(e.name)) {
                e.kind = VarRef.Kind.SETTING;
                e.slot = settingSlots.get(e.name);
                return;
            }
            if (varSlots.containsKey(e.name)) {
                e.kind = VarRef.Kind.MODULE_VAR;
                e.slot = varSlots.get(e.name);
                return;
            }
            if (HostApi.isHostFunction(e.name) || fnIndex.containsKey(e.name)) {
                throw new ScriptException("'" + e.name + "' is a function; call it with parentheses, e.g. " + e.name + "()", e.line);
            }
            throw new ScriptException("unknown name '" + e.name + "'", e.line);
        }

        private int declareLocal(String name, int line) {
            Map<String, Integer> top = scopes.peek();
            int slot = nextLocal++;
            if (nextLocal > maxLocals) maxLocals = nextLocal;
            top.put(name, slot);
            scopeVersion++;
            return slot;
        }

        private Integer lookupLocal(String name) {
            for (Map<String, Integer> scope : scopes) {
                Integer slot = scope.get(name);
                if (slot != null) return slot;
            }
            return null;
        }
    }
}
