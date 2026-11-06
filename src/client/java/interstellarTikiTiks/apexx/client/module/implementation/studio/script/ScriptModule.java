package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.event.impl.MaceHitEvent;
import interstellarTikiTiks.apexx.client.module.hooks.AttackExecutionAware;
import interstellarTikiTiks.apexx.client.module.hooks.AttackHandleAware;
import interstellarTikiTiks.apexx.client.module.hooks.AttackTargetAware;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import interstellarTikiTiks.apexx.client.module.hooks.ItemUseAware;
import interstellarTikiTiks.apexx.client.module.hooks.ItemUseBlockProvider;
import interstellarTikiTiks.apexx.client.module.hooks.JumpInputProvider;
import interstellarTikiTiks.apexx.client.module.hooks.LocalJumpListener;
import interstellarTikiTiks.apexx.client.module.hooks.MouseDeltaShaper;
import interstellarTikiTiks.apexx.client.module.hooks.MouseSensitivityModifier;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputModifier;
import interstellarTikiTiks.apexx.client.module.hooks.OutgoingPacketInterceptor;
import interstellarTikiTiks.apexx.client.module.hooks.RenderFrameAware;
import interstellarTikiTiks.apexx.client.module.hooks.WorldRenderEndAware;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.Block;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.FnDecl;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.Handler;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.ModuleDecl;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.VarDecl;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.Packet;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A real {@link Module} compiled from an Apexx script. Its settings are genuine Setting objects, so
 * they render in the module GUI and round-trip through configs; it gets the normal module keybind.
 *
 * <p>Script types: {@code toggle} (default; on/off like any module), {@code oneshot} (the keybind
 * runs {@code on trigger} once, then the script turns itself off when its {@code after} blocks
 * finish) and {@code trigger} (the module toggle is a master switch; each key press runs
 * {@code on trigger}).</p>
 *
 * <p>Every handler runs on the client thread, under this module's execution context, with an
 * instruction and time budget. A runtime fault skips that invocation and is reported to the IDE;
 * repeated budget overruns turn ONLY this script off.</p>
 */
public final class ScriptModule extends Module
        implements interstellarTikiTiks.apexx.client.module.hooks.RuntimeCreatedModule, ScriptHost, RenderFrameAware, AttackTargetAware, IncomingPacketInterceptor,
        OutgoingPacketInterceptor, AttackHandleAware, AttackExecutionAware, ItemUseBlockProvider, ItemUseAware,
        JumpInputProvider, LocalJumpListener, MovementInputModifier, MouseDeltaShaper, MouseSensitivityModifier,
        WorldRenderEndAware, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private static final int MAX_OVERRUNS = 8;
    private static final int MAX_SCHEDULED = 256;

    private final String scriptType;
    private final Setting[] settingSlots;
    /** Declared slider step per setting slot (0 when not a slider); values read by the script snap to it. */
    private final double[] sliderSteps;
    private final String sourceHash;
    private final Map<String, List<Handler>> handlers = new HashMap<>();
    private final Set<String> listened = new HashSet<>();

    private Evaluator evaluator;
    private VarDecl[] varDecls;

    private int budgetOverruns = 0;
    private long lastPrintMs = 0;
    private long enabledAtMs = 0;
    private boolean oneShotFinishPending = false;
    private boolean disableRequested = false;

    /** {@code disableScript()}: turn off at the next pump, after the running handler returns. */
    public void requestDisable() { disableRequested = true; }

    /** {@code after} blocks waiting to run. */
    private record Scheduled(long dueMs, Block body, Value[] locals) { }
    private final List<Scheduled> scheduled = new ArrayList<>();
    private final Map<Handler, Long> everyLastRun = new IdentityHashMap<>();
    private final List<String> pendingSettingChanges = new ArrayList<>();

    // Key/mouse edge detection for key_press/key_release/mouse_press/mouse_release.
    private final Set<Integer> keysDown = new HashSet<>();
    private int mouseDownMask = 0;

    // Named held inputs (direct presses are NOT auto-released by the simulator).
    private boolean holdingSneak, holdingSprint, holdingAttack, holdingUse;
    private final Set<Integer> heldKeys = new HashSet<>();

    public void noteSneak(boolean held) { holdingSneak = held; }
    public void noteSprint(boolean held) { holdingSprint = held; }
    public void noteAttack(boolean held) { holdingAttack = held; }
    public void noteUse(boolean held) { holdingUse = held; }
    public void noteKeyPress(int code) { heldKeys.add(code); }
    public void noteKeyRelease(int code) { heldKeys.remove(code); }

    ScriptModule(ModuleDecl decl, String description, Category category, Setting[] settingSlots, String sourceHash) {
        super(decl.name, description, category);
        this.scriptType = decl.scriptType == null ? "toggle" : decl.scriptType;
        this.settingSlots = settingSlots;
        this.sliderSteps = new double[settingSlots.length];
        for (int i = 0; i < settingSlots.length && i < decl.settings.size(); i++) {
            var sd = decl.settings.get(i);
            this.sliderSteps[i] = sd.kind == interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.SettingKind.SLIDER ? sd.step : 0;
        }
        this.sourceHash = sourceHash;
        for (Handler h : decl.handlers) {
            handlers.computeIfAbsent(h.event, k -> new ArrayList<>()).add(h);
            listened.add(h.event);
        }
        for (Setting s : settingSlots) {
            if (s == null) continue;
            addSetting(s);
            final String name = s.getName();
            s.onChange(() -> {
                synchronized (pendingSettingChanges) {
                    if (pendingSettingChanges.size() < 64) pendingSettingChanges.add(name);
                }
            });
        }
        // Module's constructor asked isOneShot() before scriptType was set, so the one-shot
        // "Hold to Repeat" setting is added here instead.
        if (isOneShot() && getSetting("Hold to Repeat") == null) {
            addSetting(new BooleanSetting("Hold to Repeat", false));
        }
        if (decl.defaultKey != null) {
            int code = ScriptKeys.parse(decl.defaultKey);
            if (code != ScriptKeys.UNKNOWN && getKeyCode() == -1) setKeyCode(code);
        }
    }

    void attachRuntime(ScriptCompiler.Resolved resolved, VarDecl[] varDecls) {
        this.varDecls = varDecls;
        this.evaluator = resolved.newEvaluator(this, HostApi.table());
    }

    public String getSourceHash() { return sourceHash; }

    public String getScriptType() { return scriptType; }

    /** True when any handler listens to {@code event}. */
    public boolean listens(String event) { return listened.contains(event); }

    /** Every event name this script has a handler for. */
    public Set<String> listenedEvents() { return listened; }

    // ── module type ─────────────────────────────────────────────────────────

    @Override
    public boolean isOneShot() {
        return "oneshot".equals(scriptType);
    }

    @Override
    public boolean isKeyTriggered() {
        return "trigger".equals(scriptType);
    }

    @Override
    protected void onTrigger() {
        runAll("trigger", null);
    }

    // ── ScriptHost ──────────────────────────────────────────────────────────

    @Override
    public ScriptModule module() { return this; }

    @Override
    public Value readSetting(int slot) {
        Setting s = settingSlots[slot];
        if (s instanceof SliderSetting sl) {
            double v = sl.getValue();
            double step = slot < sliderSteps.length ? sliderSteps[slot] : 0;
            if (step > 0 && Double.isFinite(step)) {
                double snapped = sl.getMin() + Math.round((v - sl.getMin()) / step) * step;
                v = Math.max(sl.getMin(), Math.min(sl.getMax(), snapped));
                v = Math.round(v * 1e6) / 1e6;
            }
            return Value.number(v);
        }
        if (s instanceof BooleanSetting bo) return Value.bool(bo.isEnabled());
        if (s instanceof ModeSetting mo) return Value.string(mo.getCurrentMode());
        if (s instanceof ColorSetting co) return Value.number(co.getValue().getRGB() & 0xFFFFFFFFL);
        return Value.NULL;
    }

    /** A slider setting's Gaussian-jittered value (its StDev companion), or its plain value. */
    public Value sampleSetting(String name) {
        Setting s = getSetting(name);
        if (s instanceof SliderSetting sl) return Value.number(sl.sample());
        for (Setting slot : settingSlots) {
            if (slot instanceof SliderSetting sl && slot.getName().equalsIgnoreCase(name)) return Value.number(sl.sample());
        }
        return Value.NULL;
    }

    @Override
    public void schedule(Block body, Value[] locals, double delayMs) {
        if (!isEnabled()) return;
        if (scheduled.size() >= MAX_SCHEDULED) {
            ScriptManager.getInstance().reportRuntimeError(getName(), -1,
                    "too many pending 'after' blocks (max " + MAX_SCHEDULED + ")");
            return;
        }
        scheduled.add(new Scheduled(System.currentTimeMillis() + Math.round(delayMs), body, locals));
    }

    /** Pending {@code after} blocks (for oneshot completion). */
    public int pendingAfterCount() { return scheduled.size(); }

    // ── lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        initModuleVars();
        budgetOverruns = 0;
        enabledAtMs = System.currentTimeMillis();
        everyLastRun.clear();
        scheduled.clear();
        keysDown.clear();
        mouseDownMask = 0;
        ScriptEvents.touch();
        if (listens("swap_lock") || listens("mace_hit")) EventBus.register(this);
        runAll("enable", null);
        if (isOneShot()) {
            runAll("trigger", null);
            oneShotFinishPending = true;
        }
    }

    @Override
    public void onDisable() {
        runAll("disable", null);
        cleanup();
    }

    @Override
    protected void onRuntimeStateReleased() {
        // A world change must not strand a oneshot (or a disableScript() request) that was waiting on
        // its 'after' blocks: those are dropped here, so finish the script now.
        boolean finish = oneShotFinishPending || disableRequested;
        cleanup();
        if (finish && isEnabled()) {
            try { setEnabled(false); } catch (Throwable ignored) {}
        }
    }

    private void cleanup() {
        scheduled.clear();
        everyLastRun.clear();
        oneShotFinishPending = false;
        disableRequested = false;
        try { releaseAllHeldInputs(); } catch (Throwable ignored) {}
        try { RotationManager.clearTarget(this); } catch (Throwable ignored) {}
    }

    private void releaseAllHeldInputs() {
        if (holdingSneak)  { try { SystemInputSimulator.releaseSneak(); }  catch (Throwable ignored) {} }
        if (holdingSprint) { try { SystemInputSimulator.releaseSprint(); } catch (Throwable ignored) {} }
        if (holdingAttack) { try { SystemInputSimulator.releaseAttack(); } catch (Throwable ignored) {} }
        if (holdingUse)    { try { SystemInputSimulator.releaseUse(); }    catch (Throwable ignored) {} }
        holdingSneak = holdingSprint = holdingAttack = holdingUse = false;
        for (Integer code : heldKeys) {
            try { SystemInputSimulator.releaseKey(code); } catch (Throwable ignored) {}
        }
        heldKeys.clear();
        try { SystemInputSimulator.releaseOwner(this); } catch (Throwable ignored) {}
    }

    @Override
    public void onTick() {
        runAll("tick", null);
        pump();
    }

    @Override
    public void onTickPost() {
        runAll("tick_post", null);
    }

    @Override
    public void onTickEnd() {
        runAll("tick_end", null);
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (listens("world_render")) fire("world_render", Value.rec().num("tickDelta", tickDelta).build());
    }

    @Override
    public void onWorldRenderEnd(float tickDelta) {
        if (listens("world_render_end")) fire("world_render_end", Value.rec().num("tickDelta", tickDelta).build());
    }

    // ── hook seams (each is a no-op unless a handler listens) ───────────────

    @Override
    public boolean handleOutgoingPacket(Packet<?> packet) {
        // Any thread: only queue. Never cancels.
        ScriptEvents.observeSent(packet);
        return false;
    }

    @Override
    public boolean handleAttack() {
        if (!listens("attack_input")) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity target = mc.crosshairTarget instanceof EntityHitResult ehr ? ehr.getEntity() : null;
        return cancelled(respond("attack_input", Value.rec().ent("target", target).build()), null);
    }

    @Override
    public void onVanillaEntityAttack(Entity target, Vec3d hitPosition) {
        if (!listens("vanilla_attack")) return;
        Value.RecordBuilder r = Value.rec().ent("target", target);
        if (hitPosition != null) r.num("x", hitPosition.x).num("y", hitPosition.y).num("z", hitPosition.z);
        fire("vanilla_attack", r.build());
    }

    @Override
    public boolean shouldBlockItemUse() {
        if (!listens("use_input")) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        String held = mc.player == null ? "air" : ScriptEvents.itemId(mc.player.getMainHandStack());
        return cancelled(respond("use_input", Value.rec().str("item", held).build()), null);
    }

    @Override
    public boolean onItemUse(BlockHitResult hit) {
        if (!listens("block_interact") || hit == null) return false;
        return cancelled(respond("block_interact", blockRecord(hit).build()), null);
    }

    @Override
    public void onPostItemUse(Hand hand, BlockHitResult hit, ActionResult result) {
        if (!listens("block_interact_post") || hit == null) return;
        fire("block_interact_post", blockRecord(hit).str("hand", hand == Hand.OFF_HAND ? "off" : "main")
                .bool("accepted", result != null && result.isAccepted()).build());
    }

    private static Value.RecordBuilder blockRecord(BlockHitResult hit) {
        BlockPos p = hit.getBlockPos();
        String block = "air";
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            if (mc.world != null) block = net.minecraft.registry.Registries.BLOCK.getId(mc.world.getBlockState(p).getBlock()).getPath();
        } catch (Throwable ignored) { }
        return Value.rec().num("x", p.getX()).num("y", p.getY()).num("z", p.getZ())
                .str("face", hit.getSide().asString()).str("block", block);
    }

    @Override
    public boolean shouldJump(MinecraftClient mc) {
        if (!listens("jump_input") || mc.player == null) return false;
        Map<String, Value> r = respond("jump_input", Value.rec().bool("onGround", mc.player.isOnGround()).build());
        Value v = r == null ? null : r.get("result");
        return v != null && v.asBool();
    }

    @Override
    public void onLocalJump(Vec3d position) {
        if (!listens("jump") || position == null) return;
        fire("jump", Value.rec().num("x", position.x).num("y", position.y).num("z", position.z).build());
    }

    @Override
    public PlayerInput modifyMovementInput(MinecraftClient mc, PlayerInput input) {
        if (!listens("movement_input") || input == null) return input;
        Map<String, Value> r = respond("movement_input", Value.rec()
                .bool("forward", input.forward()).bool("backward", input.backward())
                .bool("left", input.left()).bool("right", input.right())
                .bool("jump", input.jump()).bool("sneak", input.sneak()).bool("sprint", input.sprint()).build());
        if (r == null || r.isEmpty()) return input;
        return new PlayerInput(
                pick(r, "forward", input.forward()), pick(r, "backward", input.backward()),
                pick(r, "left", input.left()), pick(r, "right", input.right()),
                pick(r, "jump", input.jump()), pick(r, "sneak", input.sneak()), pick(r, "sprint", input.sprint()));
    }

    private static boolean pick(Map<String, Value> r, String key, boolean fallback) {
        Value v = r.get("input:" + key);
        return v == null ? fallback : v.asBool();
    }

    @Override
    public void shapeLookDelta(double[] lookDelta) {
        if (!listens("look_input") || lookDelta == null || lookDelta.length < 2) return;
        Map<String, Value> r = respond("look_input", Value.rec().num("dx", lookDelta[0]).num("dy", lookDelta[1]).build());
        if (r == null) return;
        Value dx = r.get("lookX"), dy = r.get("lookY");
        if (dx != null && Double.isFinite(dx.asNumber())) lookDelta[0] = dx.asNumber();
        if (dy != null && Double.isFinite(dy.asNumber())) lookDelta[1] = dy.asNumber();
    }

    @Override
    public float getSensitivityMultiplier() {
        if (!listens("mouse_sensitivity")) return 1.0f;
        Map<String, Value> r = respond("mouse_sensitivity", Value.rec().build());
        Value v = r == null ? null : r.get("result");
        if (v == null || !Double.isFinite(v.asNumber())) return 1.0f;
        return (float) Math.max(0.0, Math.min(1.0, v.asNumber()));
    }

    @EventTarget
    public void onSwapLockQuery(CombatSwapLockEvent event) {
        if (!listens("swap_lock") || event.isRequestedBy(this)) return;
        Map<String, Value> r = respond("swap_lock", Value.rec().bool("self", false).build());
        if (r == null) return;
        if (cancelled(r, "swap")) event.setItemSwapLocked(true);
        if (cancelled(r, "attack")) event.setAutoAttackLocked(true);
    }

    @EventTarget
    public void onMaceHitEvent(MaceHitEvent event) {
        if (listens("mace_hit")) fire("mace_hit", Value.rec().build());
    }

    // ── responses (cancel / setResult / setInput / setLook) ─────────────────

    /** Non-null only while a respondable event is being dispatched to this script. */
    private Map<String, Value> response;

    /**
     * Dispatches a respondable event and returns what its handlers asked for, or null when nothing listens
     * or the script is already running (a script's own action never re-enters its handlers).
     */
    private Map<String, Value> respond(String event, Value ev) {
        if (!isEnabled() || running > 0 || !listens(event)) return null;
        Map<String, Value> previous = response;
        response = new HashMap<>();
        try {
            fire(event, ev);
            return response;
        } finally {
            response = previous;
        }
    }

    /** Host side of cancel()/setResult()/setInput()/setLook(); ignored outside a respondable event. */
    public void putResponse(String key, Value value) {
        if (response != null && key != null) response.put(key, value == null ? Value.NULL : value);
    }

    /** True if the handlers called cancel() (any kind) or cancel(kind). */
    private static boolean cancelled(Map<String, Value> r, String kind) {
        if (r == null) return false;
        if (r.containsKey("cancel")) return true;
        return kind != null && r.containsKey("cancel:" + kind);
    }

    // ── HUD drawing (drawText/drawRect/... in HostApi) ──────────────────────

    /** Max primitive draws (fills / text runs) per render handler pass, so a loop cannot flood the GUI buffer. */
    public static final int MAX_DRAW_OPS = 4096;

    /** Non-null only while this script's {@code on render} handlers run on the client thread. */
    private DrawContext drawContext;
    private int drawOpsLeft;

    /**
     * The HUD draw context, or null outside an {@code on render} handler (draw functions are then no-ops).
     * Reserves {@code ops} primitive draws from this pass's allowance; returns null when it is used up.
     */
    public DrawContext drawContext(int ops) {
        DrawContext ctx = drawContext;
        if (ctx == null || ops < 0 || drawOpsLeft < ops) return null;
        drawOpsLeft -= ops;
        return ctx;
    }

    /** True while an {@code on render} handler of this script runs (for queries like screenWidth()). */
    public boolean inRenderPass() { return drawContext != null; }

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (!listens("render")) return;
        // SS Proof: client draws may only reach a framebuffer that is being redirected into the overlay.
        // When SS Proof is on but this pass is not redirected (e.g. a vanilla screen is open), the script's
        // handlers still run but its draw calls are no-ops, so nothing lands on the capturable frame.
        boolean drawAllowed = context != null && ScriptDraw.mayDrawThisPass();
        drawContext = drawAllowed ? context : null;
        drawOpsLeft = MAX_DRAW_OPS;
        try {
            runAll("render", null);
        } finally {
            drawContext = null;
            drawOpsLeft = 0;
        }
    }

    @Override
    public void onRenderFrame() {
        if (!isEnabled()) return;
        interstellarTikiTiks.apexx.client.util.ModuleExecutionContext.run(this, () -> {
            runAll("frame", null);
            pollInput();
            pump();
        });
    }

    @Override
    public void onAttack(Entity target) {
        if (!isEnabled() || !listens("attack")) return;
        fire("attack", Value.rec().ent("target", target).build());
    }

    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        // Network thread: only queue. Events are dispatched on the client thread by ScriptEvents.
        ScriptEvents.observePacket(packet);
        return false;
    }

    /** Timers, {@code after} blocks, setting-change events and oneshot completion. Client thread. */
    private void pump() {
        if (!isEnabled()) return;
        long now = System.currentTimeMillis();

        List<String> changes = null;
        synchronized (pendingSettingChanges) {
            if (!pendingSettingChanges.isEmpty()) {
                changes = new ArrayList<>(pendingSettingChanges);
                pendingSettingChanges.clear();
            }
        }
        if (changes != null && listens("setting_change")) {
            for (String n : changes) fire("setting_change", Value.rec().str("name", n).build());
        }

        List<Handler> every = handlers.get("every");
        if (every != null) {
            for (Handler h : every) {
                Long last = everyLastRun.get(h);
                if (last == null) {
                    everyLastRun.put(h, now);
                } else if (now - last >= h.intervalMs) {
                    everyLastRun.put(h, now);
                    run(h, Value.rec().num("elapsed", now - last).build());
                }
            }
        }

        if (!scheduled.isEmpty()) {
            List<Scheduled> due = new ArrayList<>();
            for (Iterator<Scheduled> it = scheduled.iterator(); it.hasNext(); ) {
                Scheduled s = it.next();
                if (s.dueMs <= now) {
                    due.add(s);
                    it.remove();
                }
            }
            for (Scheduled s : due) {
                if (!isEnabled()) break;
                runDebugged("after", () -> evaluator.runScheduled(s.body, s.locals));
            }
        }

        if (disableRequested) {
            disableRequested = false;
            try { setEnabled(false); } catch (Throwable ignored) {}
            return;
        }

        if (oneShotFinishPending && scheduled.isEmpty()) {
            oneShotFinishPending = false;
            try { setEnabled(false); } catch (Throwable ignored) {}
        }
    }

    /** Edge-detects keyboard keys and mouse buttons for the input events. */
    private void pollInput() {
        boolean keys = listens("key_press") || listens("key_release");
        boolean mouse = listens("mouse_press") || listens("mouse_release");
        if (!keys && !mouse) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null || mc.currentScreen != null) return;
        long window = mc.getWindow().getHandle();
        if (keys) {
            for (int code : ScriptKeys.keyboardCodes()) {
                boolean down = GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS;
                boolean was = keysDown.contains(code);
                if (down == was) continue;
                if (down) keysDown.add(code); else keysDown.remove(code);
                fire(down ? "key_press" : "key_release",
                        Value.rec().num("key", code).str("name", ScriptKeys.name(code)).build());
            }
        }
        if (mouse) {
            for (int b = 0; b <= 7; b++) {
                boolean down = GLFW.glfwGetMouseButton(window, b) == GLFW.GLFW_PRESS;
                boolean was = (mouseDownMask & (1 << b)) != 0;
                if (down == was) continue;
                mouseDownMask = down ? (mouseDownMask | (1 << b)) : (mouseDownMask & ~(1 << b));
                fire(down ? "mouse_press" : "mouse_release", Value.rec().num("button", b).build());
            }
        }
    }

    // ── dispatch ────────────────────────────────────────────────────────────

    /** Runs every handler for {@code event} with {@code ev} bound to its event variable. Client thread. */
    public void fire(String event, Value ev) {
        // Events caused synchronously by this script's own actions (e.g. its attack posting a swap-lock
        // query) are not re-delivered to it mid-handler.
        if (!isEnabled() || running > 0) return;
        List<Handler> list = handlers.get(event);
        if (list == null) return;
        interstellarTikiTiks.apexx.client.util.ModuleExecutionContext.run(this, () -> {
            for (Handler h : list) {
                if (!isEnabled()) break;
                run(h, ev);
            }
        });
    }

    private void runAll(String event, Value ev) {
        List<Handler> list = handlers.get(event);
        if (list == null) return;
        for (Handler h : list) run(h, ev);
    }

    private void run(Handler h, Value ev) {
        if (evaluator == null) return;
        runDebugged(h.event, () -> evaluator.runHandler(h.body, ev, h.paramSlot));
    }

    /** Runs a handler/after block with this script's breakpoint session (null when debugging is off). */
    private void runDebugged(String event, java.util.function.BooleanSupplier body) {
        ScriptDebugger.Session previousSession = evaluator.debugSession();
        String previousEvent = evaluator.debugEvent();
        evaluator.setDebugContext(ScriptDebugger.session(getName()), event);
        try {
            runBlock(body);
        } finally {
            evaluator.setDebugContext(previousSession, previousEvent);
        }
    }

    /** Depth of handler execution in this script (re-entrancy guard). */
    private int running;

    private void runBlock(java.util.function.BooleanSupplier body) {
        running++;
        try {
            boolean completed = body.getAsBoolean();
            if (completed) {
                budgetOverruns = 0;
            } else if (++budgetOverruns < MAX_OVERRUNS) {
                ScriptManager.getInstance().reportRuntimeError(getName(), -1,
                        "handler stopped: ran out of its instruction/time budget (" + budgetOverruns + "/" + MAX_OVERRUNS + ")");
            } else {
                ScriptManager.getInstance().reportRuntimeError(getName(), -1,
                        "turned off: ran out of its instruction/time budget " + MAX_OVERRUNS + " times in a row");
                try { setEnabled(false); } catch (Throwable ignored) {}
            }
        } catch (Evaluator.ScriptRuntimeError e) {
            ScriptManager.getInstance().reportRuntimeError(getName(), e.line, e.getMessage());
        } catch (Throwable t) {
            // Skip this invocation only, but tell the author instead of failing silently.
            ScriptManager.getInstance().reportRuntimeError(getName(), -1,
                    "internal fault in handler: " + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : ": " + t.getMessage()));
        } finally {
            running--;
        }
    }

    private void initModuleVars() {
        if (evaluator == null || varDecls == null) return;
        for (int i = 0; i < varDecls.length; i++) {
            try {
                Value v;
                try {
                    v = evaluator.evalExpression(varDecls[i].init);
                } catch (Throwable viaEvaluator) {
                    v = evalInit(varDecls[i].init); // constants-only fallback
                }
                evaluator.setModuleVar(i, v);
            } catch (Throwable t) {
                evaluator.setModuleVar(i, Value.number(0));
            }
        }
    }

    // Constant initializers (literals, negation, list literals of constants).
    private Value evalInit(interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.Expr e) {
        if (e instanceof interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.NumLit n) return Value.number(n.value);
        if (e instanceof interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.StrLit s) return Value.string(s.value);
        if (e instanceof interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.BoolLit b) return Value.bool(b.value);
        if (e instanceof interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.NullLit) return Value.NULL;
        if (e instanceof interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.ListLit l) {
            List<Value> items = new ArrayList<>();
            for (var item : l.items) items.add(evalInit(item));
            return Value.list(items);
        }
        if (e instanceof interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.Unary u) {
            Value v = evalInit(u.operand);
            if (u.op.equals("-")) return Value.number(-v.asNumber());
            return Value.bool(!v.asBool());
        }
        return Value.number(0);
    }

    /** Aim strength for aimAtEntity: a "strength" slider if declared, else 60. */
    public double getAimStrength() {
        Setting s = getSetting("strength");
        if (s instanceof SliderSetting sl) return sl.getValue();
        return 60.0;
    }

    /** Milliseconds since this script was last enabled. */
    public long uptimeMs() {
        return enabledAtMs == 0 ? 0 : System.currentTimeMillis() - enabledAtMs;
    }

    /** Rate-limited chat print used by {@code print(x)}. */
    public void scriptPrint(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastPrintMs < 500) return;
        lastPrintMs = now;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                if (interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor.isSsProofActive()) {
                    // Chat is captured by screen recording; show it in the hidden overlay instead.
                    interstellarTikiTiks.apexx.client.util.Notifier.info(getName(), String.valueOf(msg));
                } else {
                    mc.player.sendMessage(net.minecraft.text.Text.literal("[" + getName() + "] " + msg), false);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** The current value of every setting, for carrying script state across recompiles. */
    Map<String, Object> captureSettingValues() {
        Map<String, Object> out = new HashMap<>();
        for (Setting s : getSettings()) {
            if (s instanceof SliderSetting sl) out.put(s.getName(), sl.getValue());
            else if (s instanceof BooleanSetting b) out.put(s.getName(), new boolean[]{b.isEnabled()});
            else if (s instanceof ModeSetting m) out.put(s.getName(), m.getCurrentMode());
            else if (s instanceof ColorSetting c) out.put(s.getName(), c.getValue());
        }
        return out;
    }

    void restoreSettingValues(Map<String, Object> values) {
        if (values == null) return;
        for (Setting s : getSettings()) {
            Object v = values.get(s.getName());
            if (v == null) continue;
            try {
                if (s instanceof SliderSetting sl && v instanceof Double d) sl.setValue(d);
                else if (s instanceof BooleanSetting b && v instanceof boolean[] arr) b.setEnabled(arr[0]);
                else if (s instanceof ModeSetting m && v instanceof String str && m.getModes().contains(str)) m.setCurrentMode(str);
                else if (s instanceof ColorSetting c && v instanceof java.awt.Color col) c.setValue(col);
            } catch (Throwable ignored) {}
        }
    }
}
