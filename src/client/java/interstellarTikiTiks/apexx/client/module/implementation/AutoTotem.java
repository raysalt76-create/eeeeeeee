package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.ClassicIcons;
import interstellarTikiTiks.apexx.client.hud.TotemPopManager;
import interstellarTikiTiks.apexx.client.mixin.EntityStatusS2CPacketAccessor;
import interstellarTikiTiks.apexx.client.mixin.HandledScreenAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import interstellarTikiTiks.apexx.client.module.hooks.ScreenOverlayAware;
import interstellarTikiTiks.apexx.client.util.CursorMovementSimulator;
import interstellarTikiTiks.apexx.client.util.GlfwPlatform;
import interstellarTikiTiks.apexx.client.util.MobilePlatform;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.BlastModel;
import interstellarTikiTiks.apexx.client.util.combat.HumanCursorPath;
import interstellarTikiTiks.apexx.client.util.combat.InventoryClickGate;
import interstellarTikiTiks.apexx.client.util.combat.OffhandGuard;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import interstellarTikiTiks.apexx.client.util.combat.TotemDanger;
import interstellarTikiTiks.apexx.client.util.combat.TotemHumanizer;
import interstellarTikiTiks.apexx.client.util.combat.TotemInventory;
import interstellarTikiTiks.apexx.client.util.combat.TotemRoutePlanner;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps a totem where the player wants it: the offhand by default, or a chosen hotbar slot when
 * {@code Use Hotbar Slot} is on, plus an optional spare totem in the hotbar ({@code Hotbar Refill}).
 *
 * <p>Routes ({@code Refill Route}):
 * <ul>
 *   <li><b>Auto</b> (default) — the most vanilla-like route available, chosen by {@link TotemRoutePlanner}: a
 *       hotbar totem and your swap-hands key; an inventory that is already open is worked with the simulated
 *       cursor; otherwise the inventory is opened (like pressing E) and the simulated cursor moves the totem.
 *       Silent only with the Blatant {@code Allow Silent} opt-in.</li>
 *   <li><b>Swap Key</b> — your own swap-hands key with a hotbar totem; otherwise silent (opt-in) or visible.</li>
 *   <li><b>Sim Cursor</b> — always opens the inventory and moves a drawn, hand-like cursor to the totem.</li>
 *   <li><b>Visible</b> — opens the inventory and clicks directly (optionally sliding the real pointer).</li>
 *   <li><b>Silent</b> — a window click with no screen open; Blatant, needs {@code Allow Silent}.</li>
 * </ul>
 *
 * <p>Cadence: at most one window click per tick ({@link InventoryClickGate}), clicks bound to the handler they
 * were planned against, hotbar selects through {@link SlotSync} with the swap-back deferred through
 * {@link TickActionOrder}.
 *
 * <p>{@code Hover Assist} (formerly the HoverTotem module): while the player has an inventory or container screen
 * open, a totem the player's own cursor rests on is swapped into the offhand after {@code Hover Delay}. It runs in
 * {@link #runOwnInventory} next to the simulated-cursor branch (and for other handled screens from
 * {@link #runCycle}), never inside an inventory this module opened itself.
 */
public final class AutoTotem extends Module implements IncomingPacketInterceptor, ScreenOverlayAware, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {
    private static final long DELAY_MAX_CLAMP = 3000L;

    /** The verification window for a swap is the round trip plus a fixed allowance. */
    private static final long VERIFY_BASE_MS = 150L;
    private static final long VERIFY_MIN_MS = 200L;
    private static final long VERIFY_MAX_MS = 1500L;
    private static final long BACKOFF_CAP_MS = 2000L;

    /** A re-need inside this window, without a totem pop, is the server reverting the swap. */
    private static final long SILENT_REVERT_WINDOW_MS = 3000L;
    private static final int TOTEM_POP_STATUS = 35;
    private static final double HOVER_TOLERANCE = 2.0;
    private static final int ANCHOR_SCAN_RADIUS = 6;
    private static final int ANCHOR_SCAN_EVERY_TICKS = 4;
    private static final double MACE_WATCH_RADIUS = 8.0;
    private static final double MACE_BASE_DAMAGE = 6.0;
    private static final double GRAVITY = 0.08;
    /** Real-pointer travel (window pixels) that counts as the player taking over from the simulated cursor. */
    private static final double TAKEOVER_PIXELS = 6.0;
    private static final float SLOT_SIZE = 16.0f;

    private static final int GOAL_NONE = -2;
    private static final int GOAL_OFFHAND = -1;

    private static final String ROUTE_DESCRIPTION = "Auto (recommended): picks the most natural route each time - your own Swap Item With Offhand key with a hotbar totem, otherwise the inventory (opened like pressing E) with a hand-like simulated cursor. Swap Key: your swap-hands key, falling back to Silent (if allowed) or Visible. Sim Cursor: always the inventory with the simulated cursor. Visible: opens your inventory and clicks the totem directly. Silent (Blatant): a background inventory click with no screen open; needs Allow Silent.";

    // ---- Routes
    private final ModeSetting openMode = new ModeSetting("Refill Route", "Auto", "Swap Key", "Sim Cursor", "Visible", "Silent")
            .setDescription(ROUTE_DESCRIPTION);
    private String describedKey = null;
    private final BooleanSetting allowSilent = new BooleanSetting("Allow Silent", false)
            .setDescription("Blatant. Lets AutoTotem click your inventory with no screen open (Silent route, and the Swap Key fallback). Off: every inventory move happens in a real, open inventory screen.");
    private final ModeSetting simClickStyle = new ModeSetting("Cursor Click", "Swap Key", "Drag")
            .setDescription("How the simulated cursor moves the totem. Swap Key: hovers the totem and presses swap-hands, the vanilla way to send a hovered item to the offhand. Drag: picks the totem up, carries it to the offhand slot and puts it down (and returns the old offhand item).");
    private final BooleanSetting hotbarMode = new BooleanSetting("Use Hotbar Slot", false)
            .setDescription("Keeps the spare totem in a hotbar slot of your choice instead of the offhand.");
    private final SliderSetting hotbarSlot = new SliderSetting("Target Slot", 1, 1, 9, 0)
            .setDescription("The hotbar slot (1-9) that is kept filled with a totem.");
    private final BooleanSetting hotbarRefill = new BooleanSetting("Hotbar Refill", false)
            .setDescription("During calm moments keeps one totem in a hotbar slot, so the next pop can use the fast swap-hands route. Only fills an empty slot.");
    private final SliderSetting refillSlot = new SliderSetting("Refill Slot", 9, 1, 9, 0)
            .setDescription("The hotbar slot (1-9) Hotbar Refill keeps a totem in.");
    private final ModeSetting offhandPriority = new ModeSetting("Offhand Priority", "Totem Always", "Totem In Danger", "Other Modules")
            .setDescription("What may sit in your offhand. Totem Always: anything else is replaced by a totem. Totem In Danger: a shield, crystal or other item stays until you are in danger. Other Modules: only an empty offhand is filled.");
    private final BooleanSetting guardOffhand = new BooleanSetting("Guard Offhand", true)
            .setDescription("While the offhand totem is protected by Offhand Priority, other modules cannot swap it away.");
    private final BooleanSetting lockOffhand = new BooleanSetting("Lock Offhand", false)
            .setDescription("In danger, anything that replaces the offhand totem (including your own swap key) is corrected right away, whatever Offhand Priority says.");
    private final SliderSetting silentRetries = new SliderSetting("Resend Attempts", 2.0, 0.0, 5.0, 0)
            .setDescription("How many times a move the server did not take is repeated (with growing gaps) before giving up on it.");
    private final BooleanSetting legitFallback = new BooleanSetting("Fall Back To Visible", true)
            .setDescription("When the server keeps refusing background moves, switch that refill over to the visible inventory route.");
    private final BooleanSetting strictPacing = new BooleanSetting("Prefer Still Ticks", true)
            .setDescription("Inventory clicks prefer a tick in which you are not moving (released keys), up to Max Still Wait.");
    private final BooleanSetting autoOpen = new BooleanSetting("Manage Inventory", true)
            .setDescription("Lets AutoTotem open the inventory itself (like pressing E) when a totem must be moved, and close it again once it is in place.");
    private final BooleanSetting clickSim = new BooleanSetting("Mouse Events", false)
            .setDescription("Visible route: deliver inventory clicks as simulated key events rather than direct slot actions.");
    private final BooleanSetting simCursor = new BooleanSetting("Move Pointer", false)
            .setDescription("Visible route: slide your real on-screen pointer to the slot before each click.");
    private final SliderSetting cursorSpeed = new SliderSetting("Pointer Speed", 18.0, 1.0, 100.0, 0)
            .setDescription("How quickly the real pointer travels between slots.");
    private final ModeSetting cursorMode = new ModeSetting("Pointer Path", "Smooth", "Heuristic", "Instant")
            .setDescription("Shape of the real pointer's travel: Smooth glides, Heuristic follows a hand-like curve, Instant jumps.");

    // ---- Danger
    private final SliderSetting dangerHealth = new SliderSetting("Danger Health", 8.0, 0.0, 20.0, 1)
            .setDescription("At or below this health (incl. absorption), or when a nearby crystal, charged anchor or fall could kill you, the refill skips its waits.");
    private final BooleanSetting preTotem = new BooleanSetting("Pre-Totem", true)
            .setDescription("Extends the lethal-hit prediction so danger starts before the hit lands: crystals in Danger Range add up (they chain), and an elytra flight into a wall or a mace player dropping on you count too. Off: only the strongest crystal, a charged anchor and a fall are checked.");
    private final SliderSetting dangerRange = new SliderSetting("Danger Range", 12.0, 4.0, 20.0, 0)
            .setDescription("How far (blocks) end crystals are checked for a lethal blast.");
    private final SliderSetting dangerMargin = new SliderSetting("Danger Margin", 1.0, 0.0, 10.0, 1)
            .setDescription("Safety margin (hit points) added to every predicted hit before it is compared with your health.");
    private final BooleanSetting watchMace = new BooleanSetting("Watch Mace", true)
            .setDescription("Counts a player above you who holds a mace and is falling toward you as a predicted hit.");
    private final BooleanSetting watchElytra = new BooleanSetting("Watch Elytra", true)
            .setDescription("Counts gliding into a wall at lethal speed as a predicted hit.");

    // ---- Humanization
    private final SliderSetting simSpeed = new SliderSetting("Cursor Speed", 1.0, 0.3, 3.0, 2)
            .setDescription("Simulated cursor speed: 1 is an average hand; travel time follows Fitts's law (distance and slot size) divided by this.");
    private final SliderSetting curveAmount = new SliderSetting("Curve Amount %", 40.0, 0.0, 100.0, 0)
            .setDescription("How far the simulated cursor's path may bend sideways; 0 moves in straight lines.");
    private final SliderSetting overshootChance = new SliderSetting("Overshoot Chance %", 30.0, 0.0, 100.0, 0)
            .setDescription("Chance that a movement lands a little past the slot and corrects back, like a real flick.");
    private final SliderSetting cursorJitter = new SliderSetting("Cursor Jitter (px)", 0.6, 0.0, 3.0, 1)
            .setDescription("Small hand tremor on the simulated cursor, in GUI pixels.");
    private final SliderSetting missChance = new SliderSetting("Miss Chance %", 0.0, 0.0, 30.0, 0)
            .setDescription("Chance that a movement lands beside the slot and has to be corrected before clicking. Never clicks the wrong slot.");
    private final SliderSetting slotRandomness = new SliderSetting("Slot Randomness %", 25.0, 0.0, 100.0, 0)
            .setDescription("Chance to reach for another totem than the nearest one (nearer slots stay likelier).");
    private final BooleanSetting fatigue = new BooleanSetting("Fatigue", true)
            .setDescription("After several pops in a short time every pause and cursor movement gets slightly slower.");
    private final SliderSetting fatiguePerPop = new SliderSetting("Fatigue Per Pop %", 4.0, 0.0, 25.0, 0)
            .setDescription("Slow-down added per pop inside the fatigue window.");
    private final SliderSetting fatigueMax = new SliderSetting("Max Fatigue %", 25.0, 0.0, 100.0, 0)
            .setDescription("Largest total slow-down from fatigue.");
    private final SliderSetting fatigueWindow = new SliderSetting("Fatigue Window (s)", 30.0, 5.0, 120.0, 0)
            .setDescription("How long a pop keeps counting toward fatigue.");

    // ---- Delays: every wait is its own slider with a Gaussian StDev; 0 means no wait.
    private final SliderSetting fastDelay = new SliderSetting("Quick Pause (ms)", 67.0, 0.0, 300.0, 0)
            .withJitter(20.0).setSection("Delays")
            .setDescription("Usual reaction before a refill starts once the totem is found missing (Swap Key and Silent routes, and before opening the inventory).");
    private final SliderSetting slowDelay = new SliderSetting("Stumble Pause (ms)", 325.0, 0.0, 800.0, 0)
            .withJitter(50.0).setSection("Delays")
            .setDescription("Longer, occasional reaction used for the share of refills set by Stumble Rate, like a player fumbling the key.");
    private final SliderSetting fumbleChance = new SliderSetting("Stumble Rate %", 15.0, 0.0, 100.0, 0)
            .setSection("Delays")
            .setDescription("Share of steps that use the longer Stumble Pause (never in danger).");
    private final SliderSetting popDelay = new SliderSetting("Re-Totem Delay (ms)", 45.0, 0.0, 500.0, 0)
            .withJitter(15.0).setSection("Delays")
            .setDescription("Retotem reaction: wait after your totem pops before the next one is moved in.");
    private final SliderSetting maxStillWait = new SliderSetting("Max Still Wait (ms)", 150.0, 0.0, 1000.0, 0)
            .withJitter(30.0).setSection("Delays")
            .setDescription("Longest an inventory click waits for a tick in which you are not moving; then it is sent anyway.");
    private final SliderSetting retryDelay = new SliderSetting("Resend Gap (ms)", 120.0, 0.0, 500.0, 0)
            .withJitter(25.0).setSection("Delays")
            .setDescription("Base pause before a move the server did not take is repeated; doubles with each further attempt.");
    private final SliderSetting scanDelay = new SliderSetting("Scan Delay (ms)", 25.0, 0.0, 300.0, 0)
            .withJitter(10.0).setSection("Delays")
            .setDescription("Visible route: wait after the inventory is up before looking for the totem.");
    private final SliderSetting postClickDelay = new SliderSetting("Post-Click Delay (ms)", 90.0, 0.0, 500.0, 0)
            .withJitter(20.0).setSection("Delays")
            .setDescription("Visible route: wait after a click before checking the result.");
    private final SliderSetting closeDelay = new SliderSetting("Close Delay (ms)", 150.0, 0.0, 800.0, 0)
            .withJitter(35.0).setSection("Delays")
            .setDescription("Wait before closing an inventory the module opened.");
    private final SliderSetting cursorBusyDelay = new SliderSetting("Busy Cursor Wait (ms)", 200.0, 0.0, 1000.0, 0)
            .withJitter(40.0).setSection("Delays")
            .setDescription("Wait while you are carrying an item on the cursor.");
    private final SliderSetting dangerReaction = new SliderSetting("Danger Reaction (ms)", 30.0, 0.0, 200.0, 0)
            .withJitter(10.0).setSection("Delays")
            .setDescription("Reaction before a refill while in danger (low health or a predicted lethal hit), used instead of Quick/Stumble Pause and capping Cursor Reaction. 0 reacts instantly.");
    private final SliderSetting cursorReaction = new SliderSetting("Cursor Reaction (ms)", 110.0, 0.0, 600.0, 0)
            .withJitter(30.0).setSection("Delays")
            .setDescription("Sim Cursor: time between the inventory appearing and the cursor's first movement.");
    private final SliderSetting clickTiming = new SliderSetting("Click Timing (ms)", 55.0, 0.0, 300.0, 0)
            .withJitter(18.0).setSection("Delays")
            .setDescription("Sim Cursor: how long the cursor rests on a slot before the click or key press.");
    private final SliderSetting stepPause = new SliderSetting("Step Pause (ms)", 70.0, 0.0, 400.0, 0)
            .withJitter(20.0).setSection("Delays")
            .setDescription("Sim Cursor (Drag): pause after a click before the cursor moves on.");
    private final SliderSetting takeoverPause = new SliderSetting("Takeover Pause (ms)", 700.0, 0.0, 3000.0, 0)
            .withJitter(120.0).setSection("Delays")
            .setDescription("When you move the mouse or click during a simulated move, AutoTotem stops and waits this long before trying again.");
    private final SliderSetting refillCalm = new SliderSetting("Refill Calm (ms)", 1500.0, 0.0, 10000.0, 0)
            .withJitter(300.0).setSection("Delays")
            .setDescription("Hotbar Refill: how long things must be calm (no danger, no pop) before the spare totem is moved.");

    // ---- Hover Assist (formerly HoverTotem)
    private final BooleanSetting hoverAssist = new BooleanSetting("Hover Assist", true)
            .setDescription("While any inventory or container screen you opened is up, the Totem of Undying under your cursor is moved straight into your offhand after Hover Delay, so you can re-totem by pointing at one.");
    private final BooleanSetting hoverOnlyEmpty = new BooleanSetting("Hover Only If No Totem", true)
            .setDescription("Hover Assist skips the swap while your offhand already holds a totem, so a hovered totem never replaces one that is equipped.");
    private final BooleanSetting hoverAutoClose = new BooleanSetting("Hover Auto Close", false)
            .setDescription("Hover Assist closes the screen after the totem has been moved into the offhand.");
    private final SliderSetting hoverDelay = new SliderSetting("Hover Delay (ms)", 45.0, 0.0, 500.0, 0)
            .withJitter(15.0).setSection("Delays")
            .setDescription("Hover Assist: how long the cursor must rest on a totem before it is swapped, like the moment a player needs to press the offhand key. 0 swaps on the first tick it is hovered.");
    private final SliderSetting hoverSwapDelay = new SliderSetting("Hover Swap Delay (ms)", 120.0, 0.0, 1000.0, 0)
            .withJitter(30.0).setSection("Delays")
            .setDescription("Hover Assist: minimum wait before the same hovered slot can be swapped again, which stops a swap-back loop if the old offhand item lands in the hovered slot.");
    private final SliderSetting hoverCloseDelay = new SliderSetting("Hover Close Delay (ms)", 80.0, 0.0, 1000.0, 0)
            .withJitter(25.0).setSection("Delays")
            .setDescription("Hover Assist: wait between the swap and closing the screen (Hover Auto Close).");

    // ---- Visuals
    private final BooleanSetting showCursor = new BooleanSetting("Show Cursor", true)
            .setDescription("Sim Cursor moves your real mouse pointer. Where the system blocks that (Linux Wayland), this draws a cursor over the inventory instead.");
    private final BooleanSetting totemCounter = new BooleanSetting("Totem Counter", false)
            .setDescription("Shows how many totems you have left and how often you popped in this world.");
    private final SliderSetting counterX = new SliderSetting("Counter X %", 50.0, 0.0, 100.0, 0)
            .setDescription("Horizontal position of the totem counter.");
    private final SliderSetting counterY = new SliderSetting("Counter Y %", 70.0, 0.0, 100.0, 0)
            .setDescription("Vertical position of the totem counter.");
    private final BooleanSetting lowWarning = new BooleanSetting("Low Totem Warning", true)
            .setDescription("Shows a notification when your totems drop to Warn At or fewer.");
    private final SliderSetting warnAt = new SliderSetting("Warn At", 2.0, 0.0, 10.0, 0)
            .setDescription("Totem count that triggers the low warning (and turns the counter red).");
    private final BooleanSetting debug = new BooleanSetting("Chat Diagnostics", false)
            .setDescription("Print step-by-step refill details to chat.");

    /** How long after a pop a refilled destination still counts as this pop's re-totem. */
    private static final long RETOTEM_WATCH_MS = 5000L;
    /** Own pops seen in this world, for the pop toast. */
    private int worldPops;
    /** A pop toast is owed (it was held back by the throttle). */
    private boolean popToastPending;
    /** Re-totem watch: 0 none, 1 popped (destination not yet seen empty), 2 destination seen empty. */
    private int retotemWatch;
    private long retotemWatchSince;

    private final Random random = new Random();
    private final CursorMovementSimulator cursorMovement = new CursorMovementSimulator();
    private final TotemHumanizer.PopHistory popHistory = new TotemHumanizer.PopHistory();
    /** Deadline of the current step: reset on every transition, compared against {@link #stepDelayMs}. */
    private long stepStartMs;
    private long stepDelayMs;

    private int state = ST_IDLE;
    private TotemRoutePlanner.Route route = TotemRoutePlanner.Route.NONE;
    /** Where the current flow puts the totem: {@link #GOAL_OFFHAND}, a hotbar index, or {@link #GOAL_NONE}. */
    private int goal = GOAL_NONE;
    private boolean goalRefill;
    /** True when the current inventory flow uses the drawn, simulated cursor. */
    private boolean simFlow;
    private Slot targetSlot;
    private boolean openedByBot;
    /** Consecutive swaps the server did not accept; cleared when the module is enabled or the world changes. */
    private int silentFailures;
    private int flowAttempts;
    private boolean fallbackNotified;
    private long lastSilentSwapAt;
    private volatile long lastTotemPopAt;
    private volatile boolean popPending;
    private final AtomicInteger popsSeen = new AtomicInteger();
    private int popsHandled;
    /** When the current click was armed, and the sampled still-wait it may spend on the gate. */
    private long armedAtMs;
    private long stillWaitMs;
    private boolean danger;
    private TotemDanger.Level dangerLevel = TotemDanger.Level.SAFE;
    private boolean anchorLethalCached;
    private int anchorScanAge = -100;
    private long calmSinceMs;
    private long calmTargetMs = -1L;
    private long takeoverUntilMs;
    private Object lastWorld;

    // ---- simulated cursor
    private HumanCursorPath simPath;
    private long simPathStartMs;
    private double simX = Double.NaN;
    private double simY = Double.NaN;
    /** 0: to the totem, 1: to the offhand slot (Drag), 2: back to the totem's slot with the old offhand item (Drag). */
    private int simStep;
    private int simSyncId = -1;
    private int simScreenW;
    private int simScreenH;
    private double baseMouseX;
    private double baseMouseY;
    private boolean baseMouseSet;

    // ---- hover assist
    private int hoverSlotId = -1;
    private long hoverReadyAt;
    private int hoverLastSlotId = -1;
    private long hoverLastSwapAt;
    private long hoverGateMs;
    private boolean hoverClosePending;
    private long hoverCloseAt;

    // ---- visuals
    private int lastTotemCount = -1;
    private boolean lowWarned;

    public AutoTotem() {
        super("AutoTotem", "Keeps a Totem of Undying in your offhand (or a hotbar slot you pick): it predicts lethal crystals, anchors, falls, elytra crashes and mace drops, re-totems right after a pop, keeps a spare in the hotbar, and moves totems the most natural way available - your swap-hands key, or your inventory with a hand-like simulated cursor - with human reaction times.", Category.COMBAT);
        this.setSubCategory(SubCategory.SMP);
        section("Routes", this.openMode, this.allowSilent, this.simClickStyle, this.hotbarMode, this.hotbarSlot,
                this.hotbarRefill, this.refillSlot, this.offhandPriority, this.guardOffhand, this.lockOffhand,
                this.silentRetries, this.legitFallback, this.strictPacing, this.autoOpen, this.clickSim,
                this.simCursor, this.cursorSpeed, this.cursorMode);
        section("Danger", this.dangerHealth, this.preTotem, this.dangerRange, this.dangerMargin, this.watchMace,
                this.watchElytra);
        section("Humanization", this.simSpeed, this.curveAmount, this.overshootChance, this.cursorJitter,
                this.missChance, this.slotRandomness, this.fatigue, this.fatiguePerPop, this.fatigueMax,
                this.fatigueWindow);
        this.allowSilent.setBlatant(true);
        this.addSetting(this.openMode);
        this.addSetting(this.allowSilent);
        this.addSetting(this.simClickStyle);
        this.addSetting(this.hotbarMode);
        this.addSetting(this.hotbarSlot);
        this.addSetting(this.hotbarRefill);
        this.addSetting(this.refillSlot);
        this.addSetting(this.offhandPriority);
        this.addSetting(this.guardOffhand);
        this.addSetting(this.lockOffhand);
        this.addSetting(this.silentRetries);
        this.addSetting(this.legitFallback);
        this.addSetting(this.strictPacing);
        this.addSetting(this.autoOpen);
        this.addSetting(this.clickSim);
        this.addSetting(this.simCursor);
        this.addSetting(this.cursorSpeed);
        this.addSetting(this.cursorMode);
        this.addSetting(this.dangerHealth);
        this.addSetting(this.preTotem);
        this.addSetting(this.dangerRange);
        this.addSetting(this.dangerMargin);
        this.addSetting(this.watchMace);
        this.addSetting(this.watchElytra);
        this.addSetting(this.simSpeed);
        this.addSetting(this.curveAmount);
        this.addSetting(this.overshootChance);
        this.addSetting(this.cursorJitter);
        this.addSetting(this.missChance);
        this.addSetting(this.slotRandomness);
        this.addSetting(this.fatigue);
        this.addSetting(this.fatiguePerPop);
        this.addSetting(this.fatigueMax);
        this.addSetting(this.fatigueWindow);
        this.addSetting(this.fastDelay);
        this.addSetting(this.slowDelay);
        this.addSetting(this.fumbleChance);
        this.addSetting(this.popDelay);
        this.addSetting(this.maxStillWait);
        this.addSetting(this.retryDelay);
        this.addSetting(this.scanDelay);
        this.addSetting(this.postClickDelay);
        this.addSetting(this.closeDelay);
        this.addSetting(this.cursorBusyDelay);
        this.addSetting(this.dangerReaction);
        this.addSetting(this.cursorReaction);
        this.addSetting(this.clickTiming);
        this.addSetting(this.stepPause);
        this.addSetting(this.takeoverPause);
        this.addSetting(this.refillCalm);
        section("Hover Assist", this.hoverAssist, this.hoverOnlyEmpty, this.hoverAutoClose);
        this.addSetting(this.hoverAssist);
        this.addSetting(this.hoverOnlyEmpty);
        this.addSetting(this.hoverAutoClose);
        this.addSetting(this.hoverDelay);
        this.addSetting(this.hoverSwapDelay);
        this.addSetting(this.hoverCloseDelay);
        this.hoverOnlyEmpty.setVisibility(this.hoverAssist::isEnabled);
        this.hoverAutoClose.setVisibility(this.hoverAssist::isEnabled);
        this.hoverDelay.setVisibility(this.hoverAssist::isEnabled);
        this.hoverSwapDelay.setVisibility(this.hoverAssist::isEnabled);
        this.hoverCloseDelay.setVisibility(() -> this.hoverAssist.isEnabled() && this.hoverAutoClose.isEnabled());
        section("Visuals", this.showCursor, this.totemCounter, this.counterX, this.counterY, this.lowWarning,
                this.warnAt, this.debug);
        this.addSetting(this.showCursor);
        this.addSetting(this.totemCounter);
        this.addSetting(this.counterX);
        this.addSetting(this.counterY);
        this.addSetting(this.lowWarning);
        this.addSetting(this.warnAt);
        this.addSetting(this.debug);

        // Choosing the Silent route is an explicit request for silent clicks: it turns the Blatant opt-in on
        // (also when an older config that predates Allow Silent loads with Silent selected).
        this.openMode.onChange(() -> {
            if (this.openMode.is("Silent")) this.allowSilent.setEnabled(true);
        });
        this.hotbarSlot.setVisibility(this.hotbarMode::isEnabled);
        this.hotbarRefill.setVisibility(() -> !this.hotbarMode.isEnabled());
        this.refillSlot.setVisibility(() -> !this.hotbarMode.isEnabled() && this.hotbarRefill.isEnabled());
        this.offhandPriority.setVisibility(() -> !this.hotbarMode.isEnabled());
        this.guardOffhand.setVisibility(() -> !this.hotbarMode.isEnabled());
        this.lockOffhand.setVisibility(() -> !this.hotbarMode.isEnabled());
        this.simClickStyle.setVisibility(() -> this.openMode.is("Auto") || this.openMode.is("Sim Cursor"));
        this.legitFallback.setVisibility(this.allowSilent::isEnabled);
        this.maxStillWait.setVisibility(this.strictPacing::isEnabled);
        this.clickSim.setVisibility(() -> this.openMode.is("Visible") || this.openMode.is("Swap Key") || this.openMode.is("Silent"));
        this.simCursor.setVisibility(() -> this.openMode.is("Visible") || this.openMode.is("Swap Key") || this.openMode.is("Silent"));
        this.cursorSpeed.setVisibility(() -> this.simCursor.isEnabled() && this.simCursor.isVisible());
        this.cursorMode.setVisibility(() -> this.simCursor.isEnabled() && this.simCursor.isVisible());
        this.dangerRange.setVisibility(this.preTotem::isEnabled);
        this.watchMace.setVisibility(this.preTotem::isEnabled);
        this.watchElytra.setVisibility(this.preTotem::isEnabled);
        this.fatiguePerPop.setVisibility(this.fatigue::isEnabled);
        this.fatigueMax.setVisibility(this.fatigue::isEnabled);
        this.fatigueWindow.setVisibility(this.fatigue::isEnabled);
        this.refillCalm.setVisibility(this.hotbarRefill::isEnabled);
        this.counterX.setVisibility(this.totemCounter::isEnabled);
        this.counterY.setVisibility(this.totemCounter::isEnabled);
        this.warnAt.setVisibility(() -> this.lowWarning.isEnabled() || this.totemCounter.isEnabled());
    }

    private static void section(String name, interstellarTikiTiks.apexx.client.module.Setting... settings) {
        for (interstellarTikiTiks.apexx.client.module.Setting setting : settings) {
            setting.setSection(name);
        }
    }

    @Override
    public void onEnable() {
        this.silentFailures = 0;
        this.fallbackNotified = false;
        this.lastSilentSwapAt = 0L;
        this.lastTotemPopAt = 0L;
        this.popPending = false;
        this.popsHandled = this.popsSeen.get();
        this.popHistory.clear();
        this.calmSinceMs = System.currentTimeMillis();
        this.calmTargetMs = -1L;
        this.takeoverUntilMs = 0L;
        this.lowWarned = false;
        this.lastTotemCount = -1;
        this.lastWorld = this.mc.world;
        this.resetToasts();
        this.reset();
    }

    @Override
    public void onDisable() {
        OffhandGuard.release(this);
        this.reset();
        this.hoverClear();
    }

    @Override
    protected void onRuntimeStateReleased() {
        OffhandGuard.release(this);
        this.reset();
        this.hoverClear();
    }

    @Override
    public void onTick() {
        // The gate owns the per-tick key release and the quiet-tick counting; drive it first so the
        // state machine below sees this tick's state.
        InventoryClickGate.tick();
        if (this.mc.world != this.lastWorld) {
            // New world, server or dimension: nothing planned against the old one survives.
            this.lastWorld = this.mc.world;
            this.silentFailures = 0;
            this.fallbackNotified = false;
            this.popHistory.clear();
            this.resetToasts();
            this.reset();
        }
        if (isUnavailable() || this.mc.interactionManager == null) {
            OffhandGuard.release(this);
            this.danger = false;
            this.retotemWatch = 0;
            this.reset();
            return;
        }
        long now = System.currentTimeMillis();
        int pops = this.popsSeen.get();
        while (this.popsHandled < pops) {
            this.popsHandled++;
            this.popHistory.record(now);
            this.worldPops++;
            this.popToastPending = true;
            this.retotemWatch = 1;
            this.retotemWatchSince = now;
        }
        this.updateToasts(now);
        this.computeDanger();
        if (this.danger || now - this.lastTotemPopAt < 1000L) {
            this.calmSinceMs = now;
            this.calmTargetMs = -1L;
        }
        OffhandGuard.update(this, this.guardActive());
        this.updateTotemCount();
        this.refreshKeyDescription();
        this.runCycle();
    }

    /** Advances the real-pointer glide (Visible route) per frame, and draws the totem counter. */
    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (this.state == ST_ARM && !this.simFlow && this.simCursorRealEnabled()
                && this.mc.currentScreen instanceof InventoryScreen screen
                && this.targetSlot != null && screen.getScreenHandler().slots.contains(this.targetSlot)
                && this.targetSlot.hasStack() && this.targetSlot.getStack().isOf(Items.TOTEM_OF_UNDYING)
                && !isUnavailable()) {
            this.hoverReady(screen, this.targetSlot);
        } else {
            this.cursorMovement.cancel();
        }
        if (this.totemCounter.isEnabled() && context != null && this.mc.player != null
                && this.mc.currentScreen == null && this.mc.textRenderer != null) {
            this.drawCounter(context);
        }
    }

    /**
     * Moves the real pointer along the simulated path every frame. Only where the OS refuses pointer warps
     * (Wayland) is a cursor drawn over the inventory instead.
     */
    @Override
    public void onInventoryOverlay(DrawContext context, InventoryScreen screen, float delta) {
        if (!this.simFlow || MobilePlatform.isMobile()
                || this.mc.currentScreen != screen || !Double.isFinite(this.simX)) {
            return;
        }
        double[] pos = this.currentSimPos(System.currentTimeMillis());
        if (GlfwPlatform.cursorWarpSupported()) {
            this.warpRealPointer(screen, pos[0], pos[1]);
            return;
        }
        if (!this.showCursor.isEnabled()) return;
        float x = (float) pos[0];
        float y = (float) pos[1];
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        for (Slot slot : screen.getScreenHandler().slots) {
            float sx = accessor.getX() + slot.x;
            float sy = accessor.getY() + slot.y;
            if (x >= sx - 1 && x < sx + SLOT_SIZE + 1 && y >= sy - 1 && y < sy + SLOT_SIZE + 1) {
                context.fill((int) sx, (int) sy, (int) sx + 16, (int) sy + 16, 0x80FFFFFF);
                break;
            }
        }
        this.drawCursorSprite(context, x, y);
    }

    /**
     * Window clicks are sent here, at the very end of the tick, so they follow this tick's movement
     * and end-tick input packets in the same network batch.
     */
    @Override
    public void onTickEnd() {
        if (this.mc.player == null || (this.state != ST_ARM && this.state != ST_SIM_ARM)) {
            return;
        }
        if (!this.gateAllowsClick()) {
            return;
        }
        if (this.state == ST_SIM_ARM) {
            if (this.mc.currentScreen instanceof InventoryScreen screen) {
                this.performSimClick(screen);
            }
            return;
        }
        if (this.mc.currentScreen == null) {
            this.sendSilentSwap();
        } else if (this.mc.currentScreen instanceof InventoryScreen screen) {
            if (this.targetSlot != null && this.performLegitClick(screen, this.targetSlot)) {
                this.schedule(ST_SCAN, this.delay(this.postClickDelay));
            }
        }
    }

    /**
     * One click per tick always; a still tick is preferred but only waited for up to the sampled
     * Max Still Wait, and not at all in danger.
     */
    private boolean gateAllowsClick() {
        if (InventoryClickGate.clickedThisTick()) return false;
        if (!this.strictPacing.isEnabled() || InventoryClickGate.ready()) return true;
        if (this.danger) return true;
        return System.currentTimeMillis() - this.armedAtMs >= this.stillWaitMs;
    }

    private int hotbarIndex() {
        return (int) this.hotbarSlot.getValue() - 1;
    }

    private int refillIndex() {
        return (int) this.refillSlot.getValue() - 1;
    }

    private boolean isUnavailable() {
        return this.mc.player == null || this.mc.world == null || this.mc.player.isDead()
                || this.mc.player.getHealth() <= 0.0f || this.mc.player.isSpectator()
                || this.mc.player.isCreative()
                || this.mc.currentScreen instanceof DeathScreen;
    }

    /** True when the module maintains a chosen hotbar slot instead of the offhand. */
    private boolean usesHotbar() {
        return this.hotbarMode.isEnabled();
    }

    // ------------------------------------------------------------------ goals

    /** True when {@code g} already holds a totem, so there is nothing to do for it. */
    private boolean goalSatisfied(int g, boolean refill) {
        if (g == GOAL_NONE) return true;
        if (refill) return TotemInventory.hotbarHasAny(Items.TOTEM_OF_UNDYING);
        if (g == GOAL_OFFHAND) return TotemInventory.offhandHas(Items.TOTEM_OF_UNDYING);
        return TotemInventory.hotbarSlotHas(g, Items.TOTEM_OF_UNDYING);
    }

    private boolean destinationSatisfied() {
        return this.goalSatisfied(this.goal, this.goalRefill);
    }

    /** The offhand may receive a totem although it holds something else. */
    private boolean mayReplaceOffhand() {
        if (this.lockOffhand.isEnabled() && this.danger) return true;
        if (this.offhandPriority.is("Totem In Danger")) return this.danger;
        return !this.offhandPriority.is("Other Modules");
    }

    /** Whether the offhand totem is protected from other modules right now. */
    private boolean guardActive() {
        if (!this.guardOffhand.isEnabled() || this.usesHotbar()) return false;
        if (this.lockOffhand.isEnabled() && this.danger) return true;
        if (this.offhandPriority.is("Totem Always")) return true;
        return this.offhandPriority.is("Totem In Danger") && this.danger;
    }

    /** The goal a refill should serve now (offhand / chosen hotbar slot), or {@link #GOAL_NONE}. */
    private int primaryGoal() {
        if (this.usesHotbar()) {
            return TotemInventory.hotbarSlotHas(this.hotbarIndex(), Items.TOTEM_OF_UNDYING) ? GOAL_NONE : this.hotbarIndex();
        }
        if (TotemInventory.offhandHas(Items.TOTEM_OF_UNDYING)) return GOAL_NONE;
        return TotemInventory.offhandStack().isEmpty() || this.mayReplaceOffhand() ? GOAL_OFFHAND : GOAL_NONE;
    }

    /** Hotbar Refill goal during calm, or {@link #GOAL_NONE}. */
    private int refillGoal(long now) {
        if (this.usesHotbar() || !this.hotbarRefill.isEnabled() || this.danger) return GOAL_NONE;
        if (TotemInventory.hotbarHasAny(Items.TOTEM_OF_UNDYING)) return GOAL_NONE;
        int index = this.refillIndex();
        if (!TotemInventory.hotbarSlotEmpty(index)) return GOAL_NONE;
        if (TotemInventory.firstSlotMatching(s -> s.isOf(Items.TOTEM_OF_UNDYING), TotemInventory.HOTBAR_SLOTS,
                TotemInventory.MAIN_SLOTS) < 0) {
            return GOAL_NONE;
        }
        if (this.calmTargetMs < 0L) this.calmTargetMs = this.delay(this.refillCalm);
        return now - this.calmSinceMs >= this.calmTargetMs ? index : GOAL_NONE;
    }

    /** Picks the goal for a new flow; returns false when there is nothing to do. */
    private boolean pickGoal() {
        int primary = this.primaryGoal();
        if (primary != GOAL_NONE && this.hasSourceFor(primary, false)) {
            this.goal = primary;
            this.goalRefill = false;
            return true;
        }
        int refill = this.refillGoal(System.currentTimeMillis());
        if (refill != GOAL_NONE) {
            this.goal = refill;
            this.goalRefill = true;
            return true;
        }
        if (primary != GOAL_NONE && this.mc.player != null) {
            this.warnMissing("no-totem", "AutoTotem: no totem left to move.");
        }
        return false;
    }

    /** Inventory index {@code index} may be the source for goal {@code g}. */
    private static boolean sourceIndexAllowed(int index, int g, boolean refill) {
        if (index < 0 || index >= TotemInventory.MAIN_SLOTS) return false;
        if (refill) return index >= TotemInventory.HOTBAR_SLOTS;
        if (g >= 0) return index != g;
        return true;
    }

    private boolean hasSourceFor(int g, boolean refill) {
        if (this.mc.player == null) return false;
        for (int i = 0; i < TotemInventory.MAIN_SLOTS; i++) {
            if (sourceIndexAllowed(i, g, refill) && this.mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ danger

    /** Low health, or (Pre-Totem) a predicted lethal crystal, anchor, fall, elytra crash or mace drop. */
    private void computeDanger() {
        float health = this.mc.player.getHealth();
        float absorption = this.mc.player.getAbsorptionAmount();
        float incoming = this.predictedIncoming(this.preTotem.isEnabled());
        this.dangerLevel = TotemDanger.classify(health, absorption, (float) this.dangerHealth.getValue(),
                incoming, (float) this.dangerMargin.getValue());
        this.danger = this.dangerLevel != TotemDanger.Level.SAFE;
    }

    /**
     * The worst predicted hit. Always: a fall under way, the strongest single crystal in range, a lethal charged
     * anchor (the original checks). With Pre-Totem: crystals in range add up (they chain-detonate), plus an
     * elytra crash and a mace drop.
     */
    private float predictedIncoming(boolean extended) {
        float worst = this.fallIncoming();
        double range = extended ? this.dangerRange.getValue() : 12.0;
        float crystalSum = 0.0f;
        float crystalMax = 0.0f;
        for (Entity entity : this.mc.world.getOtherEntities(this.mc.player,
                this.mc.player.getBoundingBox().expand(range),
                e -> e instanceof EndCrystalEntity && e.isAlive())) {
            float blast = (float) BlastModel.damage(this.mc.world, this.mc.player, entity.getEntityPos(), 6.0f);
            crystalSum = TotemDanger.sum(crystalSum, blast);
            crystalMax = Math.max(crystalMax, blast);
        }
        worst = Math.max(worst, extended ? crystalSum : crystalMax);
        if (this.anchorLethal()) worst = Math.max(worst, Float.MAX_VALUE / 4.0f);
        if (extended && this.watchElytra.isEnabled()) worst = Math.max(worst, this.elytraIncoming());
        if (extended && this.watchMace.isEnabled()) worst = Math.max(worst, this.maceIncoming());
        return worst;
    }

    /** Fall damage at impact: the distance already fallen plus the drop still to go. */
    private float fallIncoming() {
        if (this.mc.player.isOnGround() || this.mc.player.isGliding() || this.mc.player.isTouchingWater()
                || this.mc.player.hasStatusEffect(StatusEffects.SLOW_FALLING)
                || this.mc.player.hasStatusEffect(StatusEffects.LEVITATION)
                || this.mc.player.getVelocity().y >= 0.0) {
            return 0.0f;
        }
        Vec3d from = this.mc.player.getEntityPos();
        Vec3d to = from.add(0.0, -64.0, 0.0);
        BlockHitResult hit = this.mc.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.ANY, this.mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) return 0.0f;
        if (!this.mc.world.getFluidState(hit.getBlockPos()).isEmpty()) return 0.0f;
        double drop = Math.max(0.0, from.y - hit.getPos().y);
        return TotemDanger.fallDamage(this.mc.player.fallDistance + drop, 3.0, 1.0);
    }

    /** Gliding into a block within the next few ticks at the current horizontal speed. */
    private float elytraIncoming() {
        if (!this.mc.player.isGliding()) return 0.0f;
        Vec3d v = this.mc.player.getVelocity();
        double horizontal = Math.hypot(v.x, v.z);
        float impact = TotemDanger.elytraImpactDamage(horizontal);
        if (impact <= 0.0f) return 0.0f;
        Vec3d eye = this.mc.player.getEyePos();
        double reach = Math.max(1.5, horizontal * 4.0);
        Vec3d dir = new Vec3d(v.x / horizontal, 0.0, v.z / horizontal);
        BlockHitResult hit = this.mc.world.raycast(new RaycastContext(eye, eye.add(dir.multiply(reach)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this.mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? impact : 0.0f;
    }

    /** A mace holder above and falling toward us: upper-bound smash damage at the predicted impact height. */
    private float maceIncoming() {
        float worst = 0.0f;
        Vec3d me = this.mc.player.getEntityPos();
        for (PlayerEntity other : this.mc.world.getPlayers()) {
            if (other == this.mc.player || !other.isAlive() || other.isSpectator()) continue;
            ItemStack held = other.getMainHandStack();
            if (!held.isOf(Items.MACE)) continue;
            Vec3d pos = other.getEntityPos();
            double dy = pos.y - (me.y + this.mc.player.getHeight());
            double horizontal = Math.hypot(pos.x - me.x, pos.z - me.z);
            double vy = other.getVelocity().y;
            if (dy < 0.5 || horizontal > MACE_WATCH_RADIUS || vy >= -0.1) continue;
            // Remote players' fall distance is not synced: estimate the height already fallen from the speed.
            double fallen = vy * vy / (2.0 * GRAVITY);
            double fallAtImpact = fallen + dy;
            worst = Math.max(worst, TotemDanger.maceSmashDamage(fallAtImpact, this.densityLevel(held), MACE_BASE_DAMAGE));
        }
        return worst;
    }

    private int densityLevel(ItemStack stack) {
        try {
            var registry = this.mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
            return EnchantmentHelper.getLevel(registry.getOrThrow(Enchantments.DENSITY), stack);
        } catch (RuntimeException failure) {
            return 0;
        }
    }

    /** Charged anchor nearby whose blast could kill (outside the Nether); scanned every few ticks. */
    private boolean anchorLethal() {
        int age = this.mc.player.age;
        if (Math.abs(age - this.anchorScanAge) < ANCHOR_SCAN_EVERY_TICKS) return this.anchorLethalCached;
        this.anchorScanAge = age;
        this.anchorLethalCached = false;
        if (this.mc.world.getRegistryKey() == net.minecraft.world.World.NETHER) return false;
        float pool = TotemDanger.effectiveHealth(this.mc.player.getHealth(), this.mc.player.getAbsorptionAmount());
        BlockPos origin = this.mc.player.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(origin.add(-ANCHOR_SCAN_RADIUS, -ANCHOR_SCAN_RADIUS, -ANCHOR_SCAN_RADIUS),
                origin.add(ANCHOR_SCAN_RADIUS, ANCHOR_SCAN_RADIUS, ANCHOR_SCAN_RADIUS))) {
            BlockState block = this.mc.world.getBlockState(pos);
            if (!block.isOf(Blocks.RESPAWN_ANCHOR) || block.get(RespawnAnchorBlock.CHARGES) <= 0) continue;
            if (BlastModel.damage(this.mc.world, this.mc.player, Vec3d.ofCenter(pos), BlastModel.ANCHOR_POWER)
                    + this.dangerMargin.getValue() >= pool) {
                this.anchorLethalCached = true;
                break;
            }
        }
        return this.anchorLethalCached;
    }

    // ------------------------------------------------------------------ main cycle

    private void runCycle() {
        if (this.mc.currentScreen == null) {
            this.hoverClear();
            this.runWithoutScreen();
        } else if (this.mc.currentScreen instanceof InventoryScreen screen) {
            this.runOwnInventory(screen);
        } else {
            // Some other screen is open: the refill flows never act inside the player's chest/hopper/menu
            // screens; only Hover Assist does, for a totem the player points at.
            this.reset();
            if (this.mc.currentScreen instanceof HandledScreen<?> handled) {
                this.runHoverAssist(handled);
            } else {
                this.hoverClear();
            }
        }
    }

    // ------------------------------------------------------------------ hover assist

    /**
     * Hover Assist: swaps the totem under the player's own cursor into the offhand once it has rested there for
     * Hover Delay, as one bound window click (at most one per tick). Returns true when it owns this tick (a hovered
     * totem is being waited on or was just swapped, or an auto close is pending).
     */
    private boolean runHoverAssist(HandledScreen<?> screen) {
        if (!this.hoverAssist.isEnabled() || this.mc.player == null || screen.getScreenHandler() == null) {
            this.hoverClear();
            return false;
        }
        long now = System.currentTimeMillis();
        if (this.hoverClosePending) {
            if (now < this.hoverCloseAt) return true;
            this.hoverClosePending = false;
            if (this.hoverAutoClose.isEnabled() && this.mc.currentScreen == screen) {
                this.mc.player.closeHandledScreen();
            }
            this.hoverReset();
            return true;
        }
        if (this.hoverOnlyEmpty.isEnabled() && this.mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            this.hoverReset();
            return false;
        }
        Slot slot = ((HandledScreenAccessor) screen).getFocusedSlot();
        if (slot == null || slot.id < 0 || !slot.hasStack() || !slot.getStack().isOf(Items.TOTEM_OF_UNDYING)
                || (slot.inventory == this.mc.player.getInventory()
                        && slot.getIndex() == TotemInventory.OFFHAND_INVENTORY_INDEX)) {
            this.hoverSlotId = -1;
            return false;
        }
        if (slot.id != this.hoverSlotId) {
            this.hoverSlotId = slot.id;
            this.hoverReadyAt = now + this.delay(this.hoverDelay);
        }
        if (now < this.hoverReadyAt) return true;
        if (slot.id == this.hoverLastSlotId && now - this.hoverLastSwapAt < this.hoverGateMs) return true;
        if (InventoryClickGate.clickedThisTick()) return true;
        if (!TotemInventory.clickBound(screen.getScreenHandler().syncId, slot.id,
                TotemInventory.OFFHAND_SWAP_BUTTON, SlotActionType.SWAP)) {
            return true;
        }
        this.debugLog("hover assist, slot " + slot.id);
        this.hoverLastSlotId = slot.id;
        this.hoverLastSwapAt = now;
        this.hoverGateMs = this.delay(this.hoverSwapDelay);
        if (this.hoverAutoClose.isEnabled()) {
            // Closing on a later tick also lets a still-pending paced click resolve first.
            this.hoverClosePending = true;
            this.hoverCloseAt = now + this.delay(this.hoverCloseDelay);
        }
        return true;
    }

    private void hoverReset() {
        this.hoverSlotId = -1;
        this.hoverLastSlotId = -1;
    }

    private void hoverClear() {
        this.hoverClosePending = false;
        this.hoverReset();
    }

    /** Ends a flow whose goal went away or was superseded by a more urgent one. */
    private boolean flowObsolete() {
        if (this.state == ST_IDLE || this.goal == GOAL_NONE) return false;
        if (this.goalRefill) {
            // A calm-time refill gives way to danger and to an offhand that needs a totem.
            return this.danger || this.primaryGoal() != GOAL_NONE;
        }
        return false;
    }

    // ------------------------------------------------------------------ screenless paths

    private void runWithoutScreen() {
        if (this.flowObsolete()) {
            this.reset();
            return;
        }
        if (this.state != ST_IDLE && this.state != ST_VERIFY && this.goal != GOAL_NONE && this.destinationSatisfied()
                && this.state != ST_PRE_OPEN && this.state != ST_OPEN_WAIT) {
            this.reset();
            return;
        }
        switch (this.state) {
            case ST_IDLE: {
                if (System.currentTimeMillis() < this.takeoverUntilMs || !this.pickGoal()) {
                    return;
                }
                this.flowAttempts = 0;
                this.route = TotemRoutePlanner.choose(this.planState(false, false));
                switch (this.route) {
                    case SWAP_KEY -> this.schedule(ST_KEY, this.entryDelay());
                    case SILENT -> this.schedule(ST_PRE_ARM, this.entryDelay());
                    case OPEN_VISIBLE, OPEN_SIM_CURSOR -> {
                        if (this.silentExhausted()) this.notifyFallback();
                        this.simFlow = this.route == TotemRoutePlanner.Route.OPEN_SIM_CURSOR;
                        this.schedule(ST_PRE_OPEN, this.entryDelay());
                    }
                    default -> {
                        if (!this.goalRefill) {
                            this.warnMissing("no-route", "AutoTotem: no allowed route - turn on Manage Inventory (or Allow Silent).");
                        }
                        this.goal = GOAL_NONE;
                    }
                }
                break;
            }
            case ST_PRE_ARM: {
                if (!this.stepElapsed()) return;
                this.enterArm(ST_ARM);
                break;
            }
            case ST_ARM: {
                InventoryClickGate.arm(this);
                break;
            }
            case ST_KEY: {
                if (!this.stepElapsed()) return;
                this.sendSwapKey();
                break;
            }
            case ST_VERIFY: {
                if (this.destinationSatisfied()) {
                    this.finishSwap();
                    return;
                }
                if (!this.stepElapsed()) return;
                this.silentFailures++;
                this.flowAttempts++;
                if (this.flowAttempts > (int) this.silentRetries.getValue()) {
                    this.reset();
                    return;
                }
                this.schedule(ST_RETRY, this.backoff());
                break;
            }
            case ST_RETRY: {
                if (!this.stepElapsed()) return;
                // Re-plan before another swap: a late server update must not be answered with a second
                // swap, which would pull the totem back out.
                int attempts = this.flowAttempts;
                this.state = ST_IDLE;
                if (this.pickGoal()) {
                    this.route = TotemRoutePlanner.choose(this.planState(false, false));
                    this.flowAttempts = attempts;
                    switch (this.route) {
                        case SWAP_KEY -> this.schedule(ST_KEY, 0L);
                        case SILENT -> this.enterArm(ST_ARM);
                        case OPEN_VISIBLE, OPEN_SIM_CURSOR -> {
                            this.simFlow = this.route == TotemRoutePlanner.Route.OPEN_SIM_CURSOR;
                            this.schedule(ST_PRE_OPEN, 0L);
                        }
                        default -> this.reset();
                    }
                }
                break;
            }
            case ST_PRE_OPEN: {
                if (!this.stepElapsed()) return;
                if (!this.mayOpenInventory() && !this.silentExhausted()) {
                    this.reset();
                    return;
                }
                this.openedByBot = true;
                this.mc.setScreen(new InventoryScreen(this.mc.player));
                this.schedule(ST_OPEN_WAIT, 0L);
                break;
            }
            case ST_OPEN_WAIT: {
                if (!this.stepElapsed()) return;
                // The screen never appeared (another screen took over): start clean.
                this.reset();
                break;
            }
            default: {
                // An inventory step whose screen was closed under it.
                this.reset();
                break;
            }
        }
    }

    private boolean silentExhausted() {
        int retries = (int) this.silentRetries.getValue();
        return retries > 0 && this.silentFailures >= retries;
    }

    private boolean simAvailable() {
        return !MobilePlatform.isMobile();
    }

    private boolean mayOpenInventory() {
        // Opening the inventory while riding would differ from E (that opens the mount's screen).
        return this.autoOpen.isEnabled() && this.mc.player != null && !this.mc.player.hasVehicle();
    }

    private TotemRoutePlanner.State planState(boolean ownInventoryOpen, boolean otherScreenOpen) {
        boolean offhandGoal = this.goal == GOAL_OFFHAND;
        return new TotemRoutePlanner.State(this.openMode.getCurrentMode(), offhandGoal,
                this.hasSourceFor(this.goal, this.goalRefill), this.swapKeyHotbarIndex() >= 0,
                this.swapKeyBound() && this.isGameFocused(), ownInventoryOpen, otherScreenOpen,
                this.mayOpenInventory(), this.simAvailable(), this.allowSilent.isEnabled(),
                this.silentExhausted(), this.legitFallback.isEnabled());
    }

    /** True when the player has bound "Swap Item With Offhand" to something. */
    private boolean swapKeyBound() {
        return this.mc.options != null && !this.mc.options.swapHandsKey.isUnbound();
    }

    /** Shows the key the Swap Key route will press in the route setting's description. */
    private void refreshKeyDescription() {
        if (this.mc.options == null) return;
        String key = swapKeyBound() ? this.mc.options.swapHandsKey.getBoundKeyLocalizedText().getString() : "unbound";
        if (key.equals(this.describedKey)) return;
        this.describedKey = key;
        this.openMode.setDescription(ROUTE_DESCRIPTION + " Your swap-hands key: " + key + ".");
    }

    /** Hotbar index of a totem usable by the swap-hands key (offhand goal only), or -1. */
    private int swapKeyHotbarIndex() {
        if (this.goal != GOAL_OFFHAND || this.mc.player == null) return -1;
        int selected = this.mc.player.getInventory().getSelectedSlot();
        if (TotemInventory.hotbarSlotHas(selected, Items.TOTEM_OF_UNDYING)) return selected;
        List<Integer> candidates = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        for (int i = 0; i < TotemInventory.HOTBAR_SLOTS; i++) {
            if (TotemInventory.hotbarSlotHas(i, Items.TOTEM_OF_UNDYING)) {
                candidates.add(i);
                distances.add((double) Math.abs(i - selected));
            }
        }
        if (candidates.isEmpty()) return -1;
        // Deterministic within a tick: the planner and the key press must agree on the slot.
        Random stable = new Random(this.mc.player.age * 31L + candidates.size());
        return candidates.get(TotemHumanizer.pickSlot(distances, this.slotRandomness.getValue(), stable));
    }

    /**
     * Swap Key route: select the totem's hotbar slot, then (paced after the swap, same tick) press the
     * player's own swap-hands key through the input simulator, so vanilla's input handling sends the swap
     * later this tick exactly as it does for a real key press (and honors the execution mode). The previous
     * slot is given back at the next interval through {@link TickActionOrder#deferSlot}, never in this call frame.
     */
    private void sendSwapKey() {
        int index = this.swapKeyHotbarIndex();
        if (index < 0 || !this.swapKeyBound() || !this.isGameFocused()) {
            this.state = ST_IDLE;
            return;
        }
        int previous = this.mc.player.getInventory().getSelectedSlot();
        if (index != previous) {
            if (!SlotSync.selectNow(this.mc, index)) return;
            SlotSync.paceAfterSwap();
        }
        SystemInputSimulator.tapSwapHands();
        if (index != previous) {
            TickActionOrder.deferSlot(previous, index);
        }
        this.debugLog("swap key, hotbar " + (index + 1));
        this.schedule(ST_VERIFY, this.verifyWindow());
    }

    /** Handler slot of the totem to move for the current goal, or -1. */
    private int findSourceHandlerSlot() {
        if (this.mc.player == null) return -1;
        for (int i = 0; i < TotemInventory.MAIN_SLOTS; i++) {
            if (sourceIndexAllowed(i, this.goal, this.goalRefill)
                    && this.mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) {
                return TotemInventory.handlerSlotFor(i);
            }
        }
        return -1;
    }

    private void enterArm(int armState) {
        this.state = armState;
        this.armedAtMs = System.currentTimeMillis();
        this.stillWaitMs = this.delay(this.maxStillWait);
        InventoryClickGate.arm(this);
    }

    private void sendSilentSwap() {
        int handlerSlot = this.findSourceHandlerSlot();
        if (handlerSlot < 0 || !this.allowSilent.isEnabled()) {
            this.reset();
            return;
        }
        int syncId = this.mc.player.playerScreenHandler.syncId;
        int button = this.goal == GOAL_OFFHAND ? TotemInventory.OFFHAND_SWAP_BUTTON : this.goal;
        TotemInventory.clickBound(syncId, handlerSlot, button, SlotActionType.SWAP);
        this.debugLog("silent swap from slot " + handlerSlot);
        this.schedule(ST_VERIFY, this.verifyWindow());
    }

    private void finishSwap() {
        long now = System.currentTimeMillis();
        boolean rapid = now - this.lastSilentSwapAt < SILENT_REVERT_WINDOW_MS;
        boolean popped = now - this.lastTotemPopAt < SILENT_REVERT_WINDOW_MS;
        // A quick re-need right after our own swap, with no totem pop in between, means the server
        // never took the swap: it reverted the destination instead of accepting it. A pop is a real
        // consume (the client predicts the swap, the server owns the pop), so it does not count.
        this.silentFailures = rapid && !popped ? this.silentFailures + 1 : 0;
        this.lastSilentSwapAt = now;
        this.reset();
    }

    /** Tracks own totem pops (retotem, fatigue, revert detection). Network thread. */
    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        if (packet instanceof EntityStatusS2CPacket status && status.getStatus() == TOTEM_POP_STATUS
                && this.mc.player != null
                && ((EntityStatusS2CPacketAccessor) status).getEntityId() == this.mc.player.getId()) {
            this.lastTotemPopAt = System.currentTimeMillis();
            this.popPending = true;
            this.popsSeen.incrementAndGet();
        }
        return false;
    }

    private void notifyFallback() {
        if (this.fallbackNotified || !this.debug.isEnabled()) return;
        this.fallbackNotified = true;
        Notifier.warn(this.getName(), "AutoTotem: the server ignored the silent swap, using the inventory instead.");
    }

    // ------------------------------------------------------------------ inventory paths

    /**
     * The player's own inventory is open (opened by us or by the player). The simulated cursor works it
     * on desktop for the Auto / Sim Cursor routes; otherwise the direct-click (Visible) flow does. In an
     * inventory the player opened, Hover Assist acts first on a totem the player's own cursor points at.
     */
    private void runOwnInventory(InventoryScreen screen) {
        if (this.flowObsolete()) {
            this.abortInventoryFlow();
            return;
        }
        if (this.state == ST_IDLE || this.state == ST_PRE_OPEN || this.state == ST_OPEN_WAIT
                || this.state == ST_KEY || this.state == ST_PRE_ARM || this.state == ST_RETRY
                || (this.state == ST_VERIFY && !this.simFlow)) {
            boolean ours = this.openedByBot;
            // Hover Assist: the player is pointing at a totem in an inventory they opened themselves.
            if (!ours && this.runHoverAssist(screen)) return;
            if (System.currentTimeMillis() < this.takeoverUntilMs) return;
            if (this.goal == GOAL_NONE || this.state == ST_IDLE) {
                if (!this.pickGoal()) {
                    if (ours) this.completeInventoryRefill();
                    else this.reset();
                    return;
                }
                this.flowAttempts = 0;
            }
            if (this.destinationSatisfied()) {
                this.completeInventoryRefill();
                return;
            }
            boolean wantSim = this.simAvailable() && (this.openMode.is("Auto") || this.openMode.is("Sim Cursor"));
            this.simFlow = wantSim;
            if (wantSim) {
                this.beginSimReact(screen);
            } else {
                this.schedule(ST_SCAN, this.delay(this.scanDelay));
            }
            return;
        }
        if (this.simFlow) {
            this.runSimCursor(screen);
        } else {
            this.runLegacyInventory(screen);
        }
    }

    private void runLegacyInventory(HandledScreen<?> screen) {
        if (!screen.getScreenHandler().getCursorStack().isEmpty()) {
            // The player is carrying something: never fight them for the cursor.
            InventoryClickGate.disarm(this);
            this.targetSlot = null;
            this.schedule(ST_SCAN, this.delay(this.cursorBusyDelay));
            return;
        }
        switch (this.state) {
            case ST_SCAN: {
                if (!this.stepElapsed()) return;
                if (this.destinationSatisfied()) {
                    this.completeInventoryRefill();
                    return;
                }
                Slot totem = this.findTotemSlot(screen, false);
                if (totem == null) {
                    this.completeInventoryRefill();
                    return;
                }
                this.targetSlot = totem;
                this.schedule(ST_ARM_WAIT, this.entryDelay());
                break;
            }
            case ST_ARM_WAIT: {
                if (!this.stepElapsed()) return;
                this.enterArm(ST_ARM);
                break;
            }
            case ST_ARM: {
                this.targetSlot = this.refreshTargetSlot(screen, this.targetSlot);
                if (this.targetSlot == null) {
                    this.completeInventoryRefill();
                    return;
                }
                if (!this.hoverReady(screen, this.targetSlot)) {
                    return;
                }
                InventoryClickGate.arm(this);
                break;
            }
            case ST_CLOSE: {
                if (!this.stepElapsed()) return;
                this.closeIfOurs();
                this.reset();
                break;
            }
            default: {
                this.reset();
                break;
            }
        }
    }

    /**
     * Sends the swap for {@code slot} the way vanilla would. With pointer simulation the real cursor has to
     * be over the slot first; {@code Mouse Events} then taps the real offhand key, which is what a player
     * does by hand. Returns {@code false} when the click has to wait.
     */
    private boolean performLegitClick(HandledScreen<?> screen, Slot slot) {
        if (this.simCursorRealEnabled() && !this.hoverReady(screen, slot)) {
            return false;
        }
        boolean offhandTarget = this.goal == GOAL_OFFHAND;
        if (offhandTarget && this.clickSim.isEnabled() && this.isGameFocused()
                && ((HandledScreenAccessor) screen).getFocusedSlot() == slot) {
            // The injected key makes the game send the window click itself, so the gate has to be
            // told about it here - TotemInventory is not on this path.
            SystemInputSimulator.tap(this.mc.options.swapHandsKey);
            InventoryClickGate.noteClick();
            return true;
        }
        int button = offhandTarget ? TotemInventory.OFFHAND_SWAP_BUTTON : this.goal;
        TotemInventory.clickBound(screen.getScreenHandler().syncId, slot.id, button, SlotActionType.SWAP);
        return true;
    }

    private boolean simCursorRealEnabled() {
        return this.simCursor.isEnabled() && this.simAvailable();
    }

    /** True once the real (Move Pointer) cursor actually sits on {@code slot}. */
    private boolean hoverReady(HandledScreen<?> screen, Slot slot) {
        if (!this.simCursorRealEnabled()) {
            this.cursorMovement.cancel();
            return true;
        }
        if (!this.isValidCursorTarget(screen, slot) || this.mc.getWindow() == null) {
            this.cursorMovement.cancel();
            return false;
        }
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        double targetX = (double) (accessor.getX() + slot.x) + 8.0;
        double targetY = (double) (accessor.getY() + slot.y) + 8.0;
        if (!this.cursorMovement.moveToScaled(screen.width, screen.height, targetX, targetY,
                this.cursorSpeed.getValue(), this.cursorMode.getCurrentMode())) {
            return false;
        }
        double cursorX = SystemInputSimulator.getScaledCursorX(screen.width);
        double cursorY = SystemInputSimulator.getScaledCursorY(screen.height);
        return accessor.getFocusedSlot() == slot
                && Math.abs(cursorX - targetX) <= 8.0 + HOVER_TOLERANCE
                && Math.abs(cursorY - targetY) <= 8.0 + HOVER_TOLERANCE;
    }

    private boolean isValidCursorTarget(HandledScreen<?> screen, Slot slot) {
        return this.mc.player != null && this.mc.currentScreen == screen
                && this.mc.player.currentScreenHandler == screen.getScreenHandler()
                && slot != null && screen.getScreenHandler().slots.contains(slot)
                && slot.hasStack() && slot.getStack().isOf(Items.TOTEM_OF_UNDYING);
    }

    /**
     * The totem slot to move from for the current goal: chosen like a hand would (nearest to the cursor,
     * sometimes another one, see Slot Randomness).
     */
    private Slot findTotemSlot(HandledScreen<?> screen, boolean fromSimCursor) {
        List<Slot> candidates = TotemInventory.playerSlots(screen, stack -> stack.isOf(Items.TOTEM_OF_UNDYING));
        candidates.removeIf(slot -> !sourceIndexAllowed(slot.getIndex(), this.goal, this.goalRefill));
        if (candidates.isEmpty()) return null;
        double cx;
        double cy;
        if (fromSimCursor && Double.isFinite(this.simX)) {
            cx = this.simX;
            cy = this.simY;
        } else if (this.simAvailable() && this.mc.mouse != null && this.mc.getWindow() != null) {
            cx = this.mc.mouse.getX() * screen.width / Math.max(1, this.mc.getWindow().getWidth());
            cy = this.mc.mouse.getY() * screen.height / Math.max(1, this.mc.getWindow().getHeight());
        } else {
            cx = screen.width / 2.0;
            cy = screen.height / 2.0;
        }
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        List<Double> distances = new ArrayList<>(candidates.size());
        for (Slot slot : candidates) {
            distances.add(Math.hypot(accessor.getX() + slot.x + 8.0 - cx, accessor.getY() + slot.y + 8.0 - cy));
        }
        return candidates.get(TotemHumanizer.pickSlot(distances, this.slotRandomness.getValue(), this.random));
    }

    /** Keeps the armed slot valid: the stack can be moved by the player or the server between ticks. */
    private Slot refreshTargetSlot(HandledScreen<?> screen, Slot current) {
        if (current != null && current.hasStack() && current.getStack().isOf(Items.TOTEM_OF_UNDYING)
                && sourceIndexAllowed(current.getIndex(), this.goal, this.goalRefill)) {
            return current;
        }
        return this.findTotemSlot(screen, false);
    }

    /** Refill finished: close the inventory when we opened it. */
    private void completeInventoryRefill() {
        InventoryClickGate.disarm(this);
        if (this.openedByBot) {
            // The drawn cursor stays (resting) until the screen closes.
            this.schedule(ST_CLOSE, this.delay(this.closeDelay));
            this.settleSimPos(System.currentTimeMillis());
            return;
        }
        this.reset();
    }

    /**
     * A flow inside the inventory gives up: close it if we opened it (vanilla's close hands anything still on
     * the cursor back to the inventory), otherwise just stop.
     */
    private void abortInventoryFlow() {
        if (this.openedByBot && this.mc.currentScreen instanceof InventoryScreen) {
            this.completeInventoryRefill();
            return;
        }
        this.reset();
    }

    private void closeIfOurs() {
        if (this.openedByBot && this.mc.player != null && this.mc.currentScreen != null) {
            this.mc.player.closeHandledScreen();
        }
    }

    /** True while the game window has focus, which injected key events need (no GLFW call, mobile-safe). */
    private boolean isGameFocused() {
        return this.mc.isWindowFocused();
    }

    // ------------------------------------------------------------------ simulated cursor

    private void beginSimReact(InventoryScreen screen) {
        this.simSyncId = screen.getScreenHandler().syncId;
        this.simScreenW = screen.width;
        this.simScreenH = screen.height;
        this.simStep = 0;
        this.simPath = null;
        this.captureBaseMouse();
        if (!Double.isFinite(this.simX)) {
            // The drawn cursor starts where the real pointer is (vanilla centres it when the screen opens).
            double[] real = this.realScaledMouse(screen);
            this.simX = real[0];
            this.simY = real[1];
        }
        long reaction = this.delay(this.cursorReaction);
        if (this.danger) reaction = Math.min(reaction, this.delay(this.dangerReaction));
        if (this.popPending) {
            this.popPending = false;
            reaction = Math.min(reaction, this.delay(this.popDelay));
        }
        this.targetSlot = null;
        this.schedule(ST_SIM_REACT, reaction);
    }

    private void runSimCursor(InventoryScreen screen) {
        if (this.state == ST_CLOSE) {
            if (!this.stepElapsed()) return;
            this.closeIfOurs();
            this.reset();
            return;
        }
        if (screen.getScreenHandler().syncId != this.simSyncId || screen.width != this.simScreenW
                || screen.height != this.simScreenH) {
            // Another handler or a resize: the planned coordinates are stale.
            this.abortInventoryFlow();
            return;
        }
        if (this.playerTookOver()) {
            this.debugLog("player took over the cursor");
            this.takeoverUntilMs = System.currentTimeMillis() + this.delay(this.takeoverPause);
            // The player is using this inventory now; leave it open for them.
            this.openedByBot = false;
            this.reset();
            return;
        }
        if (this.simStep == 0 && !screen.getScreenHandler().getCursorStack().isEmpty()) {
            // Something is on the cursor that we did not pick up.
            InventoryClickGate.disarm(this);
            this.schedule(ST_SIM_REACT, this.delay(this.cursorBusyDelay));
            return;
        }
        long now = System.currentTimeMillis();
        switch (this.state) {
            case ST_SIM_REACT: {
                if (!this.stepElapsed()) return;
                if (this.destinationSatisfied()) {
                    this.completeInventoryRefill();
                    return;
                }
                Slot source = this.findTotemSlot(screen, true);
                if (source == null) {
                    this.abortInventoryFlow();
                    return;
                }
                this.targetSlot = source;
                this.startPathTo(screen, source, now);
                break;
            }
            case ST_SIM_MOVE: {
                if (this.simStep == 0 && !this.isValidCursorTarget(screen, this.targetSlot)) {
                    // The totem moved away under the cursor: pick again from here.
                    this.settleSimPos(now);
                    this.schedule(ST_SIM_REACT, 0L);
                    return;
                }
                if (this.simPath != null && !this.simPath.isDone(now - this.simPathStartMs)) return;
                this.settleSimPos(now);
                this.schedule(ST_SIM_DWELL, this.delay(this.clickTiming));
                break;
            }
            case ST_SIM_DWELL: {
                if (!this.stepElapsed()) return;
                this.enterArm(ST_SIM_ARM);
                break;
            }
            case ST_SIM_ARM: {
                InventoryClickGate.arm(this);
                break;
            }
            case ST_SIM_PAUSE: {
                if (!this.stepElapsed()) return;
                Slot next = this.simStep == 1 ? screen.getScreenHandler().getSlot(TotemInventory.OFFHAND_HANDLER_SLOT)
                        : this.targetSlot;
                if (next == null) {
                    this.abortInventoryFlow();
                    return;
                }
                this.startPathTo(screen, next, now);
                break;
            }
            case ST_SIM_VERIFY: {
                if (this.destinationSatisfied() && screen.getScreenHandler().getCursorStack().isEmpty()) {
                    if (!this.stepElapsed()) return;
                    this.flowAttempts = 0;
                    this.completeInventoryRefill();
                    return;
                }
                if (!this.stepElapsed()) return;
                this.flowAttempts++;
                if (this.flowAttempts > (int) this.silentRetries.getValue()
                        || !screen.getScreenHandler().getCursorStack().isEmpty()) {
                    this.abortInventoryFlow();
                    return;
                }
                // Not taken (high ping / desync): try again from where the cursor is, after a growing gap.
                this.simStep = 0;
                this.schedule(ST_SIM_REACT, this.backoff());
                break;
            }
            default: {
                this.abortInventoryFlow();
                break;
            }
        }
    }

    /** One click of the simulated-cursor sequence, sent at tick end through the bound click seam. */
    private void performSimClick(InventoryScreen screen) {
        int syncId = screen.getScreenHandler().syncId;
        if (syncId != this.simSyncId) {
            this.abortInventoryFlow();
            return;
        }
        boolean drag = this.simClickStyle.is("Drag") && this.goal == GOAL_OFFHAND;
        switch (this.simStep) {
            case 0: {
                if (!this.isValidCursorTarget(screen, this.targetSlot)) {
                    this.schedule(ST_SIM_REACT, 0L);
                    return;
                }
                if (!drag) {
                    int button = this.goal == GOAL_OFFHAND ? TotemInventory.OFFHAND_SWAP_BUTTON : this.goal;
                    if (!TotemInventory.clickBound(syncId, this.targetSlot.id, button, SlotActionType.SWAP)) {
                        this.abortInventoryFlow();
                        return;
                    }
                    this.debugLog("cursor swap from slot " + this.targetSlot.id);
                    this.schedule(ST_SIM_VERIFY, this.verifyWindow());
                    return;
                }
                if (!TotemInventory.clickBound(syncId, this.targetSlot.id, 0, SlotActionType.PICKUP)) {
                    this.abortInventoryFlow();
                    return;
                }
                this.simStep = 1;
                this.schedule(ST_SIM_PAUSE, this.delay(this.stepPause));
                return;
            }
            case 1: {
                if (!TotemInventory.clickBound(syncId, TotemInventory.OFFHAND_HANDLER_SLOT, 0, SlotActionType.PICKUP)) {
                    this.abortInventoryFlow();
                    return;
                }
                if (!screen.getScreenHandler().getCursorStack().isEmpty() && this.targetSlot != null) {
                    // The old offhand item is now on the cursor: put it where the totem came from.
                    this.simStep = 2;
                    this.schedule(ST_SIM_PAUSE, this.delay(this.stepPause));
                    return;
                }
                this.schedule(ST_SIM_VERIFY, this.verifyWindow());
                return;
            }
            default: {
                if (this.targetSlot != null) {
                    TotemInventory.clickBound(syncId, this.targetSlot.id, 0, SlotActionType.PICKUP);
                }
                this.simStep = 0;
                this.schedule(ST_SIM_VERIFY, this.verifyWindow());
            }
        }
    }

    private void startPathTo(InventoryScreen screen, Slot slot, long now) {
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        double tx = accessor.getX() + slot.x + 8.0;
        double ty = accessor.getY() + slot.y + 8.0;
        double speed = this.simSpeed.getValue() / this.fatigueFactor();
        if (this.danger) speed *= 1.25;
        HumanCursorPath.Params params = new HumanCursorPath.Params(speed, this.curveAmount.getValue() / 100.0,
                this.overshootChance.getValue() / 100.0, this.missChance.getValue() / 100.0,
                this.cursorJitter.getValue());
        this.simPath = HumanCursorPath.plan(this.simX, this.simY, tx, ty, SLOT_SIZE, params, this.random);
        this.simPathStartMs = now;
        this.state = ST_SIM_MOVE;
        this.stepStartMs = now;
        this.stepDelayMs = 0L;
    }

    /** Current drawn cursor position: along the path while moving, otherwise where it rests. */
    private double[] currentSimPos(long now) {
        if (this.state == ST_SIM_MOVE && this.simPath != null) {
            return this.simPath.positionAt(now - this.simPathStartMs);
        }
        return new double[]{this.simX, this.simY};
    }

    private void settleSimPos(long now) {
        if (this.simPath == null) return;
        double[] pos = this.simPath.isDone(now - this.simPathStartMs)
                ? new double[]{this.simPath.endX(), this.simPath.endY()}
                : this.simPath.positionAt(now - this.simPathStartMs);
        this.simX = pos[0];
        this.simY = pos[1];
        this.simPath = null;
    }

    private double[] realScaledMouse(HandledScreen<?> screen) {
        if (this.mc.mouse == null || this.mc.getWindow() == null) {
            return new double[]{screen.width / 2.0, screen.height / 2.0};
        }
        return new double[]{
                this.mc.mouse.getX() * screen.width / Math.max(1, this.mc.getWindow().getWidth()),
                this.mc.mouse.getY() * screen.height / Math.max(1, this.mc.getWindow().getHeight())};
    }

    private void captureBaseMouse() {
        double[] real = this.glfwPointer();
        if (real == null) {
            this.baseMouseSet = false;
            return;
        }
        this.baseMouseX = real[0];
        this.baseMouseY = real[1];
        this.baseMouseSet = true;
    }

    /** The OS pointer in window coordinates, read directly so it reflects our own warps immediately. */
    private double[] glfwPointer() {
        if (this.mc.getWindow() == null) return null;
        double[] x = new double[1];
        double[] y = new double[1];
        GLFW.glfwGetCursorPos(this.mc.getWindow().getHandle(), x, y);
        return new double[]{x[0], y[0]};
    }

    /** Puts the real pointer at a scaled GUI position and remembers it, so takeover only sees the player's motion. */
    private void warpRealPointer(InventoryScreen screen, double scaledX, double scaledY) {
        if (this.mc.getWindow() == null || screen.width <= 0 || screen.height <= 0) return;
        if (this.baseMouseSet && this.playerTookOver()) return;   // the player is moving it: don't fight them
        double wx = scaledX * this.mc.getWindow().getWidth() / screen.width;
        double wy = scaledY * this.mc.getWindow().getHeight() / screen.height;
        GLFW.glfwSetCursorPos(this.mc.getWindow().getHandle(), wx, wy);
        this.baseMouseX = wx;
        this.baseMouseY = wy;
        this.baseMouseSet = true;
    }

    /** The real pointer moved or a mouse button went down since the simulated move started (desktop). */
    private boolean playerTookOver() {
        if (!this.simAvailable() || !this.baseMouseSet) return false;
        double[] real = this.glfwPointer();
        if (real == null) return false;
        if (Math.hypot(real[0] - this.baseMouseX, real[1] - this.baseMouseY) > TAKEOVER_PIXELS) {
            return true;
        }
        long handle = this.mc.getWindow().getHandle();
        return GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
    }

    private void drawCursorSprite(DrawContext context, float x, float y) {
        int scale = this.mc.getWindow() == null ? 2 : Math.max(1, this.mc.getWindow().getScaleFactor());
        float size = 39.0f / scale;
        float hot = size * (12.0f / 256.0f);
        float unit = size / 256.0f;
        Identifier sprite = ClassicIcons.texture("cursor");
        Matrix3x2fStack matrices = context.getMatrices();
        float o = Math.max(0.6f, size * 0.07f);
        for (int i = 0; i <= 8; i++) {
            boolean outline = i < 8;
            double a = i * Math.PI / 4.0;
            float ox = outline ? (float) Math.cos(a) * o : 0.0f;
            float oy = outline ? (float) Math.sin(a) * o : 0.0f;
            matrices.pushMatrix();
            matrices.translate(x - hot + ox, y - hot + oy);
            matrices.scale(unit, unit);
            context.drawTexture(RenderPipelines.GUI_TEXTURED, sprite, 0, 0, 0.0f, 0.0f, 256, 256, 256, 256,
                    outline ? 0xE6000000 : 0xFFFFFFFF);
            matrices.popMatrix();
        }
    }

    // ------------------------------------------------------------------ visuals

    private void updateTotemCount() {
        int count = TotemInventory.countItem(Items.TOTEM_OF_UNDYING);
        int threshold = (int) this.warnAt.getValue();
        boolean dropped = this.lastTotemCount >= 0 && count < this.lastTotemCount;
        if (count > threshold) {
            this.lowWarned = false;
        } else if (this.lowWarning.isEnabled() && !this.lowWarned && this.lastTotemCount >= 0
                && this.lastTotemCount != count) {
            this.lowWarned = true;
            Notifier.warn(this.getName(), "Totems left: " + count);
        }
        this.lastTotemCount = count;
    }

    private void resetToasts() {
        this.worldPops = 0;
        this.popToastPending = false;
        this.retotemWatch = 0;
        this.retotemWatchSince = 0L;
    }

    /** True when the slot AutoTotem keeps filled (offhand, or the chosen hotbar slot) holds a totem. */
    private boolean destinationHoldsTotem() {
        return this.usesHotbar()
                ? TotemInventory.hotbarSlotHas(this.hotbarIndex(), Items.TOTEM_OF_UNDYING)
                : TotemInventory.offhandHas(Items.TOTEM_OF_UNDYING);
    }

    /**
     * Pop and re-totem toasts. A pop toast waits a moment for the inventory update that follows the pop
     * (so its count is right) and pops inside the throttle window are combined into one. A re-totem only
     * counts once the popped slot was seen empty and then holds a totem again within the watch window.
     */
    private void updateToasts(long now) {
        if (this.popToastPending && now - this.lastTotemPopAt >= 100L) this.popToastPending = false;
        if (this.retotemWatch == 0) return;
        if (now - this.retotemWatchSince > RETOTEM_WATCH_MS
                || TotemInventory.countItem(Items.TOTEM_OF_UNDYING) == 0) {
            this.retotemWatch = 0;
            return;
        }
        boolean holds = this.destinationHoldsTotem();
        if (this.retotemWatch == 1 && !holds) {
            this.retotemWatch = 2;
        } else if (this.retotemWatch == 2 && holds) {
            this.retotemWatch = 0;
        }
    }

    private void drawCounter(DrawContext context) {
        int count = Math.max(0, this.lastTotemCount);
        int pops = TotemPopManager.getInstance().getPopCount(this.mc.player);
        String text = "Totems left: " + count + "  Pops: " + pops;
        int width = this.mc.textRenderer.getWidth(text);
        int sw = context.getScaledWindowWidth();
        int sh = context.getScaledWindowHeight();
        int x = (int) Math.round(sw * this.counterX.getValue() / 100.0 - width / 2.0);
        int y = (int) Math.round(sh * this.counterY.getValue() / 100.0);
        x = Math.max(1, Math.min(sw - width - 1, x));
        y = Math.max(1, Math.min(sh - 10, y));
        int color = count <= (int) this.warnAt.getValue() ? 0xFFFF5555 : 0xFFFFFFFF;
        context.drawTextWithShadow(this.mc.textRenderer, text, x, y, color);
    }

    private void debugLog(String message) {
        if (this.debug.isEnabled()) Notifier.info(this.getName(), message);
    }

    // ------------------------------------------------------------------ shared timing

    /**
     * Wait before a refill starts: the Re-Totem Delay right after a pop, the Danger Reaction in danger,
     * otherwise the quick pause (or, at Stumble Rate, the stumble pause).
     */
    private long entryDelay() {
        if (this.popPending) {
            this.popPending = false;
            return this.delay(this.popDelay);
        }
        if (this.danger) return this.delay(this.dangerReaction);
        boolean fumble = this.random.nextInt(100) < (int) this.fumbleChance.getValue();
        return this.delay(fumble ? this.slowDelay : this.fastDelay);
    }

    private double fatigueFactor() {
        if (!this.fatigue.isEnabled()) return 1.0;
        int pops = this.popHistory.countWithin(System.currentTimeMillis(), (long) (this.fatigueWindow.getValue() * 1000.0));
        return TotemHumanizer.fatigueFactor(pops, this.fatiguePerPop.getValue(), this.fatigueMax.getValue());
    }

    private long delay(SliderSetting setting) {
        double sampled = Math.max(0.0, setting.sample());
        if (setting != this.dangerReaction && setting != this.popDelay) sampled *= this.fatigueFactor();
        return Math.max(0L, Math.min(DELAY_MAX_CLAMP, Math.round(sampled)));
    }

    /** Growing gap before the next attempt of a move the server did not take. */
    private long backoff() {
        return TotemHumanizer.backoffMs(this.delay(this.retryDelay), Math.max(0, this.flowAttempts - 1), BACKOFF_CAP_MS);
    }

    /** How long a swap is given to show in its destination: the round trip plus an allowance. */
    private long verifyWindow() {
        int ping = 0;
        if (this.mc.getNetworkHandler() != null && this.mc.player != null) {
            PlayerListEntry entry = this.mc.getNetworkHandler().getPlayerListEntry(this.mc.player.getUuid());
            if (entry != null) ping = entry.getLatency();
        }
        return Math.max(VERIFY_MIN_MS, Math.min(VERIFY_MAX_MS, VERIFY_BASE_MS + ping + ping / 2));
    }

    private void schedule(int nextState, long delayMs) {
        this.state = nextState;
        this.stepDelayMs = Math.max(0L, delayMs);
        this.stepStartMs = System.currentTimeMillis();
    }

    private boolean stepElapsed() {
        return System.currentTimeMillis() - this.stepStartMs >= this.stepDelayMs;
    }

    /** Ends the current flow but keeps what has been learned about this server. */
    private void reset() {
        InventoryClickGate.disarm(this);
        this.cursorMovement.cancel();
        this.state = ST_IDLE;
        this.route = TotemRoutePlanner.Route.NONE;
        this.goal = GOAL_NONE;
        this.goalRefill = false;
        this.simFlow = false;
        this.simPath = null;
        this.simX = Double.NaN;
        this.simY = Double.NaN;
        this.simStep = 0;
        this.simSyncId = -1;
        this.baseMouseSet = false;
        this.stepDelayMs = 0L;
        this.stepStartMs = System.currentTimeMillis();
        this.targetSlot = null;
        this.openedByBot = false;
    }

    private static final int ST_IDLE = 0;
    private static final int ST_PRE_OPEN = 1;
    private static final int ST_OPEN_WAIT = 2;
    private static final int ST_SCAN = 3;
    private static final int ST_ARM = 4;
    private static final int ST_VERIFY = 5;
    private static final int ST_CLOSE = 6;
    private static final int ST_RETRY = 7;
    private static final int ST_PRE_ARM = 8;
    private static final int ST_KEY = 9;
    private static final int ST_ARM_WAIT = 10;
    private static final int ST_SIM_REACT = 11;
    private static final int ST_SIM_MOVE = 12;
    private static final int ST_SIM_DWELL = 13;
    private static final int ST_SIM_ARM = 14;
    private static final int ST_SIM_VERIFY = 15;
    private static final int ST_SIM_PAUSE = 16;
}
