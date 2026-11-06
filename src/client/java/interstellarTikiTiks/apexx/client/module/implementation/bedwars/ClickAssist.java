package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.RenderFrameAware;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Adds an extra click after some of your real clicks.
 *
 * <p>The extra click is never in the same millisecond as yours (a dead-giveaway double-click): it is
 * scheduled a random fraction of your own measured click interval later, and cancelled if your next real
 * click comes first. The attack side is cooldown-aware by default, because in 1.9+ a click on an
 * uncharged weapon only throws damage away; the use side respects vanilla's use cooldown. Clicks go
 * through the shared input seams, never an OS-level robot.
 */
public class ClickAssist extends Module implements RenderFrameAware, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final SliderSetting chance = new SliderSetting("Chance %", 70, 0, 100, 0)
            .setDescription("Probability that a real click gets an extra click after it.");

    private final BooleanSetting left = new BooleanSetting("Left Click", true)
            .setDescription("Assist your attack clicks.");
    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", true)
            .setDependsOn("Left Click")
            .setDescription("Only assist attack clicks while holding a sword, axe, mace, spear or trident.");
    private final BooleanSetting onlyTargeting = new BooleanSetting("Only On Entities", true)
            .setDependsOn("Left Click")
            .setDescription("Only add an attack click while the crosshair is on an entity (a 1.9+ miss resets the attack charge).");
    private final BooleanSetting cooldownAware = new BooleanSetting("Cooldown Aware", true)
            .setDependsOn("Left Click")
            .setDescription("Only add an attack click once the weapon has recharged to the charge threshold, so the extra click deals real damage.");
    private final SliderSetting chargeThreshold = new SliderSetting("Charge Threshold %", 90, 50, 100, 0)
            .setDependsOn("Cooldown Aware")
            .setDescription("How recharged the weapon must be for an extra attack click.");
    private final BooleanSetting ignoreFriends = new BooleanSetting("Ignore Friends", true)
            .setDependsOn("Left Click")
            .setDescription("Never add an attack click on a player from your friend list.");

    private final BooleanSetting right = new BooleanSetting("Right Click", false)
            .setDescription("Assist your use clicks (block placing). Vanilla's use cooldown still applies.");
    private final BooleanSetting blocksOnly = new BooleanSetting("Blocks Only", true)
            .setDependsOn("Right Click")
            .setDescription("Only assist use clicks while holding a placeable block.");
    private final SliderSetting minRightCps = new SliderSetting("Min Real Right CPS", 0, 0, 10, 0)
            .setDependsOn("Right Click")
            .setDescription("Only assist when your real right-click rate over the last second is at least this. 0 = always.");

    private final SliderSetting offsetPct = new SliderSetting("Extra Click Offset %", 40, 10, 80, 0).withJitter(10)
            .setSection("Delays")
            .setDescription("When the extra click lands, as a percentage of your measured click interval after the real click.");
    private final SliderSetting minGap = new SliderSetting("Min Gap (ms)", 30, 15, 150, 0).withJitter(8)
            .setSection("Delays")
            .setDescription("The extra click is never closer than this to your real click, ms. Below about 15 ms it reads as a mechanical double-click.");

    private boolean leftWasDown;
    private boolean rightWasDown;
    private long lastLeftMs;
    private long lastRightMs;
    private double leftIntervalEma = 120.0;
    private double rightIntervalEma = 150.0;
    private long pendingLeftAt = -1L;
    private long pendingRightAt = -1L;
    private int lastActionTick = Integer.MIN_VALUE;
    private final Deque<Long> rightClicks = new ArrayDeque<>();

    public ClickAssist() {
        super("ClickAssist", "Adds a natural extra click a moment after some of your real clicks. Cooldown-aware on attacks so the extra hit deals damage, and paced off your own click rhythm so it never looks like a mechanical double-click.", Category.BEDWARS);
        addSetting(chance);
        addSetting(left);
        addSetting(weaponOnly);
        addSetting(onlyTargeting);
        addSetting(cooldownAware);
        addSetting(chargeThreshold);
        addSetting(ignoreFriends);
        addSetting(right);
        addSetting(blocksOnly);
        addSetting(minRightCps);
        addSetting(offsetPct);
        addSetting(minGap);
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        reset();
    }

    @Override
    protected void onRuntimeStateReleased() {
        reset();
    }

    private void reset() {
        leftWasDown = false;
        rightWasDown = false;
        pendingLeftAt = -1L;
        pendingRightAt = -1L;
        lastActionTick = Integer.MIN_VALUE;
        rightClicks.clear();
    }

    @Override
    public void onRenderFrame() {
        if (mc.player == null || mc.world == null || mc.options == null) return;
        long now = System.currentTimeMillis();
        if (mc.currentScreen != null) {
            reset();
            return;
        }
        boolean leftDown = SystemInputSimulator.isPhysicallyPressed(mc.options.attackKey);
        boolean rightDown = SystemInputSimulator.isPhysicallyPressed(mc.options.useKey);

        if (leftDown && !leftWasDown) onRealLeft(now);
        if (rightDown && !rightWasDown) onRealRight(now);
        leftWasDown = leftDown;
        rightWasDown = rightDown;
        while (!rightClicks.isEmpty() && now - rightClicks.peekFirst() > 1000L) rightClicks.pollFirst();

        if (pendingLeftAt >= 0L && now >= pendingLeftAt) {
            pendingLeftAt = -1L;
            if (mc.player.age != lastActionTick && leftAllowed(true)) {
                if (AttackValidator.tryAttack(mc, "bedwars.clickassist")) lastActionTick = mc.player.age;
            }
        }
        if (pendingRightAt >= 0L && now >= pendingRightAt) {
            pendingRightAt = -1L;
            if (mc.player.age != lastActionTick && rightAllowed() && InteractSim.useReady(mc)
                    && !mc.player.isUsingItem()) {
                ClickSimController.use(mc);
                lastActionTick = mc.player.age;
            }
        }
    }

    private void onRealLeft(long now) {
        if (lastLeftMs > 0L) leftIntervalEma = ema(leftIntervalEma, now - lastLeftMs);
        lastLeftMs = now;
        pendingLeftAt = -1L;
        if (!left.isEnabled() || !leftAllowed(false) || !ClickTiming.roll(chance.getValue() / 100.0)) return;
        pendingLeftAt = now + offsetFor(leftIntervalEma);
    }

    private void onRealRight(long now) {
        if (lastRightMs > 0L) rightIntervalEma = ema(rightIntervalEma, now - lastRightMs);
        lastRightMs = now;
        rightClicks.addLast(now);
        pendingRightAt = -1L;
        if (!right.isEnabled() || !rightAllowed() || !ClickTiming.roll(chance.getValue() / 100.0)) return;
        if (minRightCps.getValue() > 0 && rightClicks.size() < minRightCps.getValue()) return;
        pendingRightAt = now + offsetFor(rightIntervalEma);
    }

    private long offsetFor(double intervalEma) {
        double pct = Math.max(5.0, Math.min(95.0, offsetPct.sample())) / 100.0;
        return Math.round(Math.max(minGap.sample(), intervalEma * pct));
    }

    private static double ema(double prev, long sample) {
        if (sample <= 0L || sample > 1000L) return prev;
        return prev * 0.7 + sample * 0.3;
    }

    private boolean leftAllowed(boolean atFire) {
        if (mc.player.isUsingItem()) return false;
        if (weaponOnly.isEnabled() && !LeftClicker.isWeapon(mc.player.getMainHandStack())) return false;
        HitResult hit = mc.crosshairTarget;
        boolean onEntity = hit != null && hit.getType() == HitResult.Type.ENTITY;
        if (onlyTargeting.isEnabled() && !onEntity) return false;
        if (onEntity && ignoreFriends.isEnabled() && ((EntityHitResult) hit).getEntity() instanceof PlayerEntity p
                && FriendManager.getInstance().isFriend(p.getUuid())) {
            return false;
        }
        if (atFire && cooldownAware.isEnabled()
                && mc.player.getAttackCooldownProgress(0.0f) < chargeThreshold.getValue() / 100.0) {
            return false;
        }
        return true;
    }

    private boolean rightAllowed() {
        return !blocksOnly.isEnabled() || mc.player.getMainHandStack().getItem() instanceof BlockItem;
    }
}
