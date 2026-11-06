package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.RenderFrameAware;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Attack auto-clicker for while you hold the attack button.
 *
 * <p>Cooldown mode (the 1.9+ combat default) strikes once the weapon has recharged to a jittered charge
 * threshold, after a human reaction delay, so no damage is wasted on a half-charged swing. CPS mode is for
 * legacy-combat servers and clicks at a humanized rate drawn per click from a Gaussian, butterfly or
 * jitter pattern, with burst fatigue, dropped clicks and hesitation spikes. Every strike goes through
 * {@link AttackValidator} (crosshair, wire-ray and gating checks, Execution Mode), never more than once
 * per client tick.
 */
public class LeftClicker extends Module implements RenderFrameAware, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    // Bedwars is 1.8 combat (Hypixel and most Bedwars servers run legacy PvP via ViaVersion): there is no attack
    // cooldown, so CPS is the default. Cooldown mode stays for the rare 1.9+ Bedwars variant.
    private final ModeSetting mode = new ModeSetting("Mode", "CPS", "CPS", "Cooldown")
            .setDescription("CPS: click at a humanized rate, the right choice for 1.8 (legacy) Bedwars combat where hits have no cooldown. Cooldown: strike only when the weapon is recharged, for 1.9+ combat servers.");
    private final SliderSetting chargeThreshold = new SliderSetting("Charge Threshold %", 92, 50, 100, 0).withJitter(3)
            .setDependsOn("Mode:Cooldown")
            .setDescription("How recharged the weapon must be before a strike, in percent. 100 = full damage; around 85 trades a little damage for tempo. The StDev varies it per strike.");
    private final SliderSetting minCps = new SliderSetting("Min CPS", 9, 1, 20, 1)
            .setDependsOn("Mode:CPS")
            .setDescription("Lower end of the clicks-per-second band.");
    private final SliderSetting maxCps = new SliderSetting("Max CPS", 13, 1, 20, 1)
            .setDependsOn("Mode:CPS")
            .setDescription("Upper end of the clicks-per-second band.");
    private final ModeSetting pattern = new ModeSetting("Pattern", ClickTiming.GAUSSIAN, ClickTiming.GAUSSIAN, ClickTiming.BUTTERFLY, ClickTiming.JITTER)
            .setDependsOn("Mode:CPS")
            .setDescription("Gaussian: steady rate around the middle of the band. Butterfly: clicks in short/long pairs. Jitter: a wider, occasionally erratic spread.");
    private final SliderSetting fatigue = new SliderSetting("Burst Fatigue %/s", 4, 0, 15, 1)
            .setDependsOn("Mode:CPS")
            .setDescription("Rate lost per second of continuous clicking after the first second (never below 70% of the band). Resets when you release.");
    private final SliderSetting dropChance = new SliderSetting("Missed Click %", 2, 0, 15, 1)
            .setDescription("Chance that a due click is skipped, like a finger that did not fully press.");
    private final SliderSetting spikeChance = new SliderSetting("Hesitation %", 6, 0, 30, 1)
            .setDescription("Chance that an interval gets a hesitation spike (see Hesitation Length).");

    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", false)
            .setDescription("Only click while holding a sword, axe, mace, spear or trident.");
    private final BooleanSetting breakBlocks = new BooleanSetting("Break Blocks", true)
            .setDescription("While the crosshair is on a block, leave the button to vanilla so the block is mined instead of re-clicked.");
    private final BooleanSetting skipAir = new BooleanSetting("Skip Air Clicks", true)
            .setDescription("Only strike when the crosshair is on an entity. A 1.9+ miss resets the attack charge, so air clicks cost damage.");
    private final BooleanSetting ignoreFriends = new BooleanSetting("Ignore Friends", true)
            .setDescription("Never click while the crosshair is on a player from your friend list.");
    private final BooleanSetting pauseWhileUsing = new BooleanSetting("Pause While Using", true)
            .setDescription("Do not click while you are eating, drinking, drawing a bow or holding up a shield.");
    private final ModeSetting hitSelect = new ModeSetting("Hit Select", "Off", "Off", "After Hit")
            .setDescription("After Hit: while you hold the button, nothing is clicked until the opponent lands a hit on you; from then until you release, clicking runs normally.");
    private final SliderSetting hurtWindow = new SliderSetting("Hit Window (ticks)", 7, 1, 10, 0)
            .setDependsOn("Hit Select:After Hit")
            .setDescription("How recent the opponent's hit must be, in ticks (a hit's hurt animation lasts 10).");

    private final SliderSetting reactionDelay = new SliderSetting("Reaction Delay (ms)", 35, 0, 250, 0).withJitter(15)
            .setSection("Delays")
            .setDescription("Cooldown mode: wait after the weapon reaches the charge threshold before striking, like a human noticing the recharge.");
    private final SliderSetting startDelay = new SliderSetting("Start Delay (ms)", 40, 0, 400, 0).withJitter(12)
            .setSection("Delays")
            .setDescription("Wait after you first press the button before the clicker joins in, so a single tap is never turned into a burst.");
    private final SliderSetting spikeLength = new SliderSetting("Hesitation Length (ms)", 80, 10, 400, 0).withJitter(25)
            .setSection("Delays")
            .setDescription("Extra time added to an interval when a hesitation spike happens.");

    private final ClickTiming timing = new ClickTiming();
    private boolean wasHeld;
    private long pressedAtMs;
    private long nextClickMs;
    private long chargedSinceMs = -1L;
    private double currentThreshold = Double.NaN;
    private boolean hitLatched;
    private int lastClickTick = Integer.MIN_VALUE;

    public LeftClicker() {
        super("LeftClicker", "Humanized attack auto-clicker for while you hold attack. Cooldown mode strikes at a jittered charge threshold so 1.9+ hits keep their damage; CPS mode clicks at a natural, fatiguing rate for legacy-combat servers.", Category.BEDWARS);
        addSetting(mode);
        addSetting(chargeThreshold);
        addSetting(minCps);
        addSetting(maxCps);
        addSetting(pattern);
        addSetting(fatigue);
        addSetting(dropChance);
        addSetting(spikeChance);
        addSetting(weaponOnly);
        addSetting(breakBlocks);
        addSetting(skipAir);
        addSetting(ignoreFriends);
        addSetting(pauseWhileUsing);
        addSetting(hitSelect);
        addSetting(hurtWindow);
        addSetting(reactionDelay);
        addSetting(startDelay);
        addSetting(spikeLength);
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        resetState();
    }

    private void resetState() {
        timing.reset();
        wasHeld = false;
        pressedAtMs = 0L;
        nextClickMs = 0L;
        chargedSinceMs = -1L;
        currentThreshold = Double.NaN;
        hitLatched = false;
        lastClickTick = Integer.MIN_VALUE;
    }

    @Override
    public void onRenderFrame() {
        if (mc.player == null || mc.world == null || mc.options == null) return;
        long now = System.currentTimeMillis();
        boolean held = mc.currentScreen == null && SystemInputSimulator.isPhysicallyPressed(mc.options.attackKey);
        if (!held) {
            if (wasHeld) resetState();
            return;
        }
        if (!wasHeld) {
            wasHeld = true;
            pressedAtMs = now;
            nextClickMs = now + Math.round(startDelay.sample());
            timing.reset();
        }
        if (now < nextClickMs) return;
        if (mc.player.age == lastClickTick) return;
        if (!passesHitSelect()) return;
        if (pauseWhileUsing.isEnabled() && mc.player.isUsingItem()) return;
        if (weaponOnly.isEnabled() && !isWeapon(mc.player.getMainHandStack())) return;

        HitResult hit = mc.crosshairTarget;
        if (hit == null) return;
        if (hit.getType() == HitResult.Type.BLOCK && breakBlocks.isEnabled()
                && !mc.world.getBlockState(((BlockHitResult) hit).getBlockPos()).isAir()) {
            return;
        }
        Entity target = hit.getType() == HitResult.Type.ENTITY ? ((EntityHitResult) hit).getEntity() : null;
        if (target instanceof PlayerEntity p && ignoreFriends.isEnabled()
                && FriendManager.getInstance().isFriend(p.getUuid())) {
            return;
        }

        if (mode.is("Cooldown")) {
            if (target == null && skipAir.isEnabled()) return;
            if (Double.isNaN(currentThreshold)) currentThreshold = Math.min(100.0, chargeThreshold.sample()) / 100.0;
            float charge = mc.player.getAttackCooldownProgress(0.0f);
            if (charge < currentThreshold) {
                chargedSinceMs = -1L;
                return;
            }
            if (chargedSinceMs < 0L) {
                chargedSinceMs = now + Math.round(reactionDelay.sample());
            }
            if (now < chargedSinceMs) return;
            if (ClickTiming.roll(dropChance.getValue() / 100.0)) {
                chargedSinceMs = now + Math.round(reactionDelay.sample());
                return;
            }
            if (strike()) {
                currentThreshold = Double.NaN;
                chargedSinceMs = -1L;
            }
            return;
        }

        if (target == null && skipAir.isEnabled() && hit.getType() != HitResult.Type.BLOCK) {
            scheduleNext(now);
            return;
        }
        if (ClickTiming.roll(dropChance.getValue() / 100.0)) {
            scheduleNext(now);
            return;
        }
        strike();
        scheduleNext(now);
    }

    private void scheduleNext(long now) {
        nextClickMs = now + timing.nextIntervalMs(now, minCps.getValue(), maxCps.getValue(),
                pattern.getCurrentMode(), fatigue.getValue(), spikeChance.getValue() / 100.0, spikeLength.sample());
    }

    private boolean strike() {
        if (!AttackValidator.tryAttack(mc, "bedwars.leftclicker")) return false;
        lastClickTick = mc.player.age;
        return true;
    }

    private boolean passesHitSelect() {
        if (!hitSelect.is("After Hit")) return true;
        if (hitLatched) return true;
        if (mc.player.hurtTime >= Math.max(1, 10 - (int) hurtWindow.getValue()) && mc.player.hurtTime > 0) {
            hitLatched = true;
            return true;
        }
        return false;
    }

    static boolean isWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.isIn(ItemTags.SWORDS) || stack.getItem() instanceof AxeItem || stack.isOf(Items.MACE)
                || stack.isOf(Items.TRIDENT) || isSpear(stack);
    }

    private static boolean isSpear(ItemStack stack) {
        String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
        return id.endsWith("_spear");
    }
}
