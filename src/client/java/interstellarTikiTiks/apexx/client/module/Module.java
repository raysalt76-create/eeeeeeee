package interstellarTikiTiks.apexx.client.module;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.ApexxAuth;
import interstellarTikiTiks.apexx.client.module.hooks.NotificationSink;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public abstract class Module {
    private static final Logger LOGGER = org.slf4j.helpers.NOPLogger.NOP_LOGGER;
    private static final int MODULE_ENABLE_GATE_SALT = ((0x4F ^ 0x1A) << 24)
            | ((0x8D ^ 0xC2) << 16)
            | ((0x37 ^ 0x6B) << 8)
            | (0xE1 ^ 0xB7);
    protected MinecraftClient mc = MinecraftClient.getInstance();

    private String name;
    private String description;
    private final Category category;
    private SubCategory subCategory = SubCategory.GENERAL;
    private boolean enabled;
    // Locked by an overriding module: while true, any enable
    // request is ignored so the module stays pinned in its current state. Every
    // toggle vector (keybind poll, GUI click, config-sync load) funnels through
    // setEnabled, so this single guard blocks them all at once.
    private boolean locked = false;
    // Hidden modules are client/config utilities (GUI toggles, stream-proof, self-
    // destruct, config binds) — they still function and persist, but the module LIST
    // and the arraylist omit them; the GUI surfaces them under the settings gear
    // instead, so they don't clutter the combat/render module grid.
    private boolean hidden = false;
    private boolean expanded;
    public double animatedHeight;
    private int keyCode = -1;
    private int keyCode2 = -1;
    private boolean holdMode = false;
    private boolean binding;
    public double settingsAnimation = 0.0;
    private long bindStartTime = 0;

    private final List<Setting> settings = new ArrayList<>();

    public Module(String name, String description, Category category) {
        this(name, description, category, SubCategory.GENERAL);
    }

    public Module(String name, String description, Category category, SubCategory subCategory) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.subCategory = subCategory == null ? SubCategory.GENERAL : subCategory;
        this.animatedHeight = 14;

        try {
            addSetting(new interstellarTikiTiks.apexx.client.module.implementation.KeybindSetting("Keybind", this));
        } catch (Exception ignored) {}
        // One-shot modules expose a "Hold to Repeat" toggle: off (default) fires one
        // action per key press (Tap); on repeats the action while the key is held.
        // Done here so every one-shot module gets it without a per-module edit and it
        // auto-surfaces in the synced GUI like any other setting. Safe to call the
        // overridable isOneShot() from the constructor: it returns a constant and
        // reads no subclass field.
        if (isOneShot()) {
            addSetting(new BooleanSetting("Hold to Repeat", false));
        }
        // Every module that clicks, uses items or swaps slots gets its own "Click Sim" toggle, the
        // same way one-shot modules get "Hold to Repeat". There is no client-wide switch.
        if (this instanceof interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating) {
            clickSimSetting = new BooleanSetting("Click Sim", false)
                    .setDescription("Sends this module's clicks and key presses as real input with a short hold, so keystroke overlays show them. Off: the module acts directly and nothing shows on a keystrokes display.");
            addSetting(clickSimSetting);
        }
    }

    /** This module's "Click Sim" toggle; null when the module never clicks for the player. */
    private BooleanSetting clickSimSetting;

    /** True when this module's own "Click Sim" toggle is on. */
    public boolean isClickSimEnabled() {
        return clickSimSetting != null && clickSimSetting.isEnabled();
    }

    /**
     * Uniform draw from [min, max].
     *
     * <p>This was named {@code gaussianRandom} while being a flat uniform draw, and at
     * at least one module relied on it believing the distribution was
     * normal. Uniform inter-action delays are also the easier pattern for an anticheat
     * to spot -- a real hand produces a bell, not a rectangle -- so callers that want
     * human-looking spread should use {@link #gaussianBetween} instead.
     */
    protected double uniformRandom(double min, double max) {
        if (Math.abs(max - min) < 0.0001) {
            return min;
        }
        return min + (max - min) * ThreadLocalRandom.current().nextDouble();
    }

    /** Compatibility alias for uniformRandom. */
    @Deprecated
    protected double gaussianRandom(double min, double max) {
        return uniformRandom(min, max);
    }

    /**
     * Normal draw centred on the midpoint of [min, max], clamped to those bounds.
     *
     * <p>Sigma is a sixth of the range, so the bounds sit at +-3 sigma and ~99.7% of
     * draws land inside without the clamp having to do any work; the clamp only
     * catches the rare tail. Deliberately keeps the (min, max) shape of
     * {@link #uniformRandom} so swapping one for the other at a call site cannot
     * change what the arguments mean.
     */
    protected double gaussianBetween(double min, double max) {
        if (Math.abs(max - min) < 0.0001) {
            return min;
        }
        double lo = Math.min(min, max);
        double hi = Math.max(min, max);
        double mid = (lo + hi) * 0.5;
        double sigma = (hi - lo) / 6.0;
        double draw = mid + ThreadLocalRandom.current().nextGaussian() * sigma;
        return Math.max(lo, Math.min(hi, draw));
    }

    public Module(String name, Category category) {
        this(name, "", category);
    }

    protected void addSetting(Setting setting) {
        settings.add(setting);
        if (setting != null) setting.captureDefault();
        if (setting instanceof SliderSetting slider && slider.getJitter() != null
                && !settings.contains(slider.getJitter())) {
            SliderSetting jitter = slider.getJitter();
            if (jitter.getSection() == null && setting.getSection() != null) jitter.setSection(setting.getSection());
            if (setting.isAdvanced()) jitter.setAdvanced(true);
            settings.add(jitter);
            jitter.captureDefault();
        }
    }

    /** Minimum gap between two repeats of the same missing-requirement warning. */
    private static final long WARN_MISSING_INTERVAL_MS = 10_000L;
    /** key -> last time (ms) that warning was shown. Cleared on enable, so a fresh enable warns at once. */
    private final java.util.Map<String, Long> missingWarnings = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Tells the user why the module is enabled but doing nothing (missing item, missing
     * condition). Safe to call every tick: each {@code key} is shown at most once per
     * {@value #WARN_MISSING_INTERVAL_MS} ms, and the throttle resets on every enable of a
     * normal module. One-shot modules re-enable on every key press, so for them the
     * throttle is kept across enables and a held key cannot spam the feed. Callers keep
     * their own early return; this only reports.
     */
    protected void warnMissing(String key, String message) {
        if (key == null || message == null) return;
        long now = System.currentTimeMillis();
        Long last = missingWarnings.get(key);
        if (last != null && now - last < WARN_MISSING_INTERVAL_MS) return;
        missingWarnings.put(key, now);
        try {
            String title = getName();
            if (mc != null && !mc.isOnThread()) {
                mc.execute(() -> interstellarTikiTiks.apexx.client.util.Notifier.warn(title, message));
            } else {
                interstellarTikiTiks.apexx.client.util.Notifier.warn(title, message);
            }
        } catch (Throwable ignored) {}
    }

    public void onEnable() {
    }

    public void onDisable() {
    }

    public void onTick() {
    }

    public void onTickPost() {
    }

    /** Tail of the client tick, after that tick's movement and input packets were queued. */
    public void onTickEnd() {
    }

    public void onRender() {
    }

    public void onRender(DrawContext context, float tickDelta) {
        onRender();
    }

    public void onWorldRender(MatrixStack matrices, float tickDelta) {
    }

    /**
     * Whether this module renders visible world-space 3D geometry during onWorldRender.
     * Used by the stream-proof visual capture layer to determine if the overlay window
     * needs to be kept open and updated during gameplay.
     */
    public boolean rendersInWorld() {
        return category == Category.RENDER;
    }

    public void onMs() {
    }

    public boolean shouldTickWhenDisabled() {
        return false;
    }

    /**
     * One-shot modules perform a single, self-terminating action when enabled and
     * then disable themselves (pearl / insta-catch / macro modules). For these the
     * keybind behaves as a momentary trigger, not a stateful toggle: a fresh press
     * fires exactly one action and a press never cancels an in-flight one. They also
     * get a "Hold to Repeat" setting (see the constructor) that switches the bind
     * between fire-once-per-press (Tap) and repeat-while-held. Overridden to
     * {@code true} by such modules; read by the keybind poll in MinecraftClientMixin.
     */
    public boolean isOneShot() {
        return false;
    }

    /**
     * Key-triggered modules keep a normal on/off toggle (the GUI button, {@link #isEnabled()}) as a
     * master switch, and their keybind no longer toggles them: a press (or hold, per the module) calls
     * {@link #onTrigger()}, and only while the module is enabled. Overridden to {@code true} by such
     * modules; read by the keybind poll in MinecraftClientMixin.
     */
    public boolean isKeyTriggered() {
        return false;
    }

    /** True for a key-triggered module that acts for as long as its bind is held (otherwise it fires per press). */
    public boolean isTriggerHold() {
        return false;
    }

    /** True while a key-triggered module is mid-action (a hold session or a tap sequence in flight). */
    public boolean isTriggerActive() {
        return false;
    }

    /** Short HUD label for what the bind does: HOLD, TAP, REPEAT or TOGGLE. */
    public String getBindModeLabel() {
        if (isKeyTriggered() || isOneShot()) {
            if (isTriggerHold()) return "HOLD";
            return isOneShotRepeat() ? "REPEAT" : "TAP";
        }
        return holdMode ? "HOLD" : "TOGGLE";
    }

    /** The action behind the keybind of a key-triggered module. Runs on the client thread, enabled modules only. */
    protected void onTrigger() {
    }

    /** Dispatches {@link #onTrigger()} under this module's execution context, fault-isolated. */
    public final void trigger() {
        if (!enabled || !ApexxAuth.isAuthenticated()) return;
        try {
            interstellarTikiTiks.apexx.client.util.ModuleExecutionContext.run(this, this::onTrigger);
        } catch (Throwable t) {
            ClientInternalLog.error(getName(), "Trigger failed: " + t.getMessage(), t);
            releaseRuntimeState();
        }
    }

    /**
     * Marks a module as an explicit, user-visible exception to the vanilla-cadence rules. The
     * ClickGUI shows a "BLATANT" tag on its card (accent colour, purely informational). Overridden
     * to {@code true} only by modules the project has deliberately exempted.
     */
    public boolean isBlatantFlagged() {
        return false;
    }

    /**
     * For one-shot modules: whether the action should repeat for as long as the
     * keybind is held (the "Hold to Repeat" toggle on) rather than firing once per
     * press (toggle off / Tap, the default). Always false for non-one-shot modules,
     * which have no such setting. Read by the keybind poll in MinecraftClientMixin.
     */
    public boolean isOneShotRepeat() {
        Setting s = getSetting("Hold to Repeat");
        return s instanceof BooleanSetting && ((BooleanSetting) s).isEnabled();
    }

    /**
     * Whether this module's keybind (both keys of a two-key bind) is physically down right now. False
     * when unbound or while a screen is open. Lets a one-shot sequence tell a held key from a tap.
     */
    public boolean isBindHeld() {
        if (mc.getWindow() == null || mc.currentScreen != null) return false;
        if (!isKeyDown(keyCode)) return false;
        return keyCode2 == -1 || keyCode2 == 0 || isKeyDown(keyCode2);
    }

    private boolean isKeyDown(int key) {
        if (key == -1 || key == 0) return false;
        if (key < 0) {
            int button = mouseButtonOf(key);
            return button >= 0 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST
                    && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), button) == GLFW.GLFW_PRESS;
        }
        return key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST && InputUtil.isKeyPressed(mc.getWindow(), key);
    }

    public void toggle() {
        setEnabled(!this.isEnabled());
    }

    public String getName() {
        return name;
    }

    /** True for client/config utilities that live under the settings gear, not in the module list. */
    public boolean isHidden() {
        return hidden;
    }

    public Module setHidden(boolean h) {
        this.hidden = h;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public SubCategory getSubCategory() {
        return subCategory;
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T setSubCategory(SubCategory subCategory) {
        this.subCategory = subCategory == null ? SubCategory.GENERAL : subCategory;
        return (T) this;
    }

    public Category getCategory() {
        return category;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public boolean isLocked() {
        return locked;
    }

    /**
     * Locks/unlocks this module against external enable requests. While locked,
     * setEnabled(true) from any source (keybind, GUI, config sync) is ignored so an
     * overriding module can keep it pinned off. The locker disables the
     * module first and is responsible for restoring it on unlock.
     */
    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public void setEnabled(boolean enabled) {
        if (locked && enabled) {
            // Pinned by an overriding module — ignore any external re-enable attempt.
            return;
        }
        if (this.enabled == enabled)
            return;
        if (enabled && !canEnableModuleNow())
            return;
        this.enabled = enabled;
        if (this.enabled && !isOneShot()) {
            missingWarnings.clear();
        }
        if (this.enabled) {
            try {
                interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard.clearOwner(this);
                if (Apexx.getInstance() != null && Apexx.getInstance().getModuleManager() != null) {
                    Apexx.getInstance().getModuleManager().unpark(getName());
                }
            } catch (Throwable ignored) {}
        }
        boolean lifecycleSucceeded = false;
        try {
            if (this.enabled) {
                interstellarTikiTiks.apexx.client.util.ModuleExecutionContext.run(this, this::onEnable);
                ClientInternalLog.info(getName(), "Module enabled");
            } else {
                interstellarTikiTiks.apexx.client.util.ModuleExecutionContext.run(this, this::onDisable);
                ClientInternalLog.info(getName(), "Module disabled");
            }
            lifecycleSucceeded = true;
        } catch (Throwable t) {
            ClientInternalLog.error(getName(), "Module lifecycle failed while " + (this.enabled ? "enabling" : "disabling") + ": " + t.getMessage(), t);
            LOGGER.debug("Module lifecycle failed for [{}] while {}.", getName(),
                    this.enabled ? "enabling" : "disabling", t);
            

            if (this.enabled) {
                this.enabled = false;
            }
        } finally {
            if (!this.enabled || !lifecycleSucceeded) {
                releaseRuntimeState();
                interstellarTikiTiks.apexx.client.event.EventBus.unregister(this);
            }
        }
        if (!lifecycleSucceeded) return;

        // One-shot modules enable and immediately disable themselves on every
        // action, so a toast pair ("Enabled"/"Disabled") would fire for each pearl
        // catch / macro run and spam the feed — suppress notifications for them.
        if (isOneShot())
            return;

        try {
            if (Apexx.getInstance().getModuleManager() == null) {
                return;
            }
            NotificationSink notif = Apexx.getInstance().getModuleManager().getHook(NotificationSink.class);
            if (notif != null) {
                if (this.enabled) {
                    notif.show(this.getName(), "Enabled", "SUCCESS");
                } else {
                    notif.show(this.getName(), "Disabled", "ERROR");
                }
            }
        } catch (Exception e) {

        }
    }

    /** Feature-state cleanup, also called on faults and world changes. Must be idempotent. */
    protected void onRuntimeStateReleased() {}

    /** Releases shared resources that must never survive a module disable or callback fault. */
    public final void releaseRuntimeState() {
        try {
            interstellarTikiTiks.apexx.client.util.ModuleExecutionContext.run(this, this::onRuntimeStateReleased);
        } catch (Throwable t) {
            ClientInternalLog.error(getName(), "Failed to release feature state", t);
        }
        try {
            interstellarTikiTiks.apexx.client.util.RotationManager.abortOwner(this);
        } catch (Throwable t) {
            ClientInternalLog.error(getName(), "Failed to release rotation ownership", t);
        }
        try {
            interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer.cancelOwner(this);
        } catch (Throwable t) {
            ClientInternalLog.error(getName(), "Failed to cancel delayed actions", t);
        }
        try {
            interstellarTikiTiks.apexx.client.util.SystemInputSimulator.releaseOwner(this);
        } catch (Throwable t) {
            ClientInternalLog.error(getName(), "Failed to release simulated input", t);
        }
        try {
            interstellarTikiTiks.apexx.client.util.combat.SlotSync.releaseOwner(this);
        } catch (Throwable t) {
            ClientInternalLog.error(getName(), "Failed to release hotbar ownership", t);
        }
    }

    private static boolean canEnableModuleNow() {
        // Fail-closed authority: a module may only enable when a live, verified, HWID-bound
        // server session grant exists. Report-only (ENFORCE=false) keeps this permissive but
        // logs the would-be denial; once ENFORCE is flipped, a leaked/pasted-config jar cannot
        // enable ANY module. This is the single funnel setEnabled() routes through.
        return ApexxAuth.isAuthenticated();
    }

    protected boolean gateHighValueAction(String featureName, String actionKey, String scopeKey) {
        String normalizedAction = actionKey == null ? "" : actionKey.trim().toLowerCase(java.util.Locale.ROOT);
        String context = ApexxAuth.buildFeaturePermitContext(featureName, normalizedAction, scopeKey);
        if (context == null || context.isBlank()) {
            return false;
        }
        return ApexxAuth.hasFeatureActionPermitFast(featureName, normalizedAction, context);
    }

    protected void warmHighValueActionPermit(String featureName, String actionKey, String scopeKey) {
        String normalizedAction = actionKey == null ? "" : actionKey.trim().toLowerCase(java.util.Locale.ROOT);
        String context = ApexxAuth.buildFeaturePermitContext(featureName, normalizedAction, scopeKey);
        if (context == null || context.isBlank()) {
            return;
        }
        ApexxAuth.prefetchFeatureActionPermit(featureName, normalizedAction, context);
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public Setting getSetting(String name) {
        for (Setting setting : this.settings) {
            if (setting.getName().equalsIgnoreCase(name)) {
                return setting;
            }
        }
        return null;
    }

    public List<Setting> getSettings() {
        return settings;
    }

    public int getKeyCode() {
        return keyCode;
    }

    public int getKey() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        if (keyCode == 0) {
            this.keyCode = -1;
            return;
        }
        this.keyCode = keyCode;
    }

    public int getKeyCode2() {
        return keyCode2;
    }

    public void setKeyCode2(int keyCode2) {
        this.keyCode2 = (keyCode2 == 0) ? -1 : keyCode2;
    }

    public boolean isHoldMode() {
        return holdMode;
    }

    public void setHoldMode(boolean holdMode) {
        this.holdMode = holdMode;
    }

    // Starred in the ClickGUI; pure UI metadata, persisted with the module's config entry.
    private boolean favorite;

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public boolean isBinding() {
        return binding;
    }

    public void setBinding(boolean binding) {
        this.binding = binding;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getKeyDisplayName() {
        if (keyCode == -1 || keyCode == 0) {
            return "NONE";
        }
        String first = getSingleKeyName(keyCode);
        if (keyCode2 != -1) {
            return first + " + " + getSingleKeyName(keyCode2);
        }
        return first;
    }

    /** Bind codes below -1 store mouse button b as -(b + 100); this inverts that (0 = left, 3 = side back, 4 = side forward). */
    public static int mouseButtonOf(int key) {
        return -key - 100;
    }

    public static String getSingleKeyName(int key) {
        if (key < 0) {
            int button = mouseButtonOf(key);
            return "MOUSE" + (button + 1);
        }
        switch (key) {
            case GLFW.GLFW_KEY_RIGHT_SHIFT:
                return "RSHIFT";
            case GLFW.GLFW_KEY_LEFT_SHIFT:
                return "LSHIFT";
            case GLFW.GLFW_KEY_RIGHT_CONTROL:
                return "RCTRL";
            case GLFW.GLFW_KEY_LEFT_CONTROL:
                return "LCTRL";
            case GLFW.GLFW_KEY_RIGHT_ALT:
                return "RALT";
            case GLFW.GLFW_KEY_LEFT_ALT:
                return "LALT";
            case GLFW.GLFW_KEY_ESCAPE:
                return "ESC";
            case GLFW.GLFW_KEY_DELETE:
                return "DELETE";
            case GLFW.GLFW_KEY_INSERT:
                return "INSERT";
            case GLFW.GLFW_KEY_PAGE_UP:
                return "PAGE UP";
            case GLFW.GLFW_KEY_PAGE_DOWN:
                return "PAGE DOWN";
            case GLFW.GLFW_KEY_PRINT_SCREEN:
                return "PRINT SCREEN";
            default:
                String name = GLFW.glfwGetKeyName(key, 0);
                return name == null ? "UNKNOWN" : name.toUpperCase();
        }
    }

    public long getBindStartTime() {
        return bindStartTime;
    }

    public void setBindStartTime(long bindStartTime) {
        this.bindStartTime = bindStartTime;
    }
}
