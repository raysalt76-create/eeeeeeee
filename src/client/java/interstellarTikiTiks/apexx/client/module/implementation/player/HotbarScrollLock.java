package interstellarTikiTiks.apexx.client.module.implementation.player;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackExecutionAware;
import interstellarTikiTiks.apexx.client.module.hooks.HotbarScrollBlocker;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Stops the mouse wheel from changing your hotbar slot while you are fighting (or always), so a stray
 * scroll mid-fight cannot pull your weapon away. Number keys, module-driven slot swaps, GUI scrolling
 * and the spectator menu keep working.
 */
public final class HotbarScrollLock extends Module implements HotbarScrollBlocker, AttackExecutionAware {
    private final ModeSetting mode = new ModeSetting("Mode", "In Combat", "In Combat", "Always")
            .setDescription("In Combat locks the wheel only for a while after you hit or are hit; Always locks it whenever the module is on.");
    private final SliderSetting combatWindow = new SliderSetting("Combat Window (s)", 4.0, 0.5, 20.0, 1)
            .setDescription("How long after the last hit (given or taken) you count as in combat.")
            .setDependsOn("Mode:In Combat");
    private final BooleanSetting onAttack = new BooleanSetting("On Attack", true)
            .setDescription("Hitting a living target starts or extends combat.")
            .setDependsOn("Mode:In Combat");
    private final BooleanSetting onHurt = new BooleanSetting("On Hurt", true)
            .setDescription("Taking damage starts or extends combat.")
            .setDependsOn("Mode:In Combat");
    private final BooleanSetting playersOnly = new BooleanSetting("Players Only", true)
            .setDescription("Only fights with players count: hitting a player, or damage caused by a player "
                    + "(including their arrows). Fall, fire and mob damage are ignored.")
            .setDependsOn("Mode:In Combat");

    private long lastCombatMs;
    private int lastHurtTime;
    private ClientWorld lastWorld;

    public HotbarScrollLock() {
        super("HotbarScrollLock", "Stops the mouse wheel from switching your hotbar slot while you are in combat "
                + "(or always), so a stray scroll cannot pull your weapon away mid-fight. Number keys, other modules' "
                + "slot swaps, GUI scrolling and the spectator menu are unaffected.", Category.PLAYER);
        addSetting(mode);
        addSetting(combatWindow);
        addSetting(onAttack);
        addSetting(onHurt);
        addSetting(playersOnly);
    }

    @Override
    public boolean rendersInWorld() {
        return false;
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
        lastCombatMs = 0L;
        lastHurtTime = 0;
        lastWorld = null;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            resetState();
            return;
        }
        if (mc.world != lastWorld) {
            resetState();
            lastWorld = mc.world;
        }
        int hurt = mc.player.hurtTime;
        // hurtTime jumps back up to maxHurtTime on every new hit and counts down otherwise.
        if (hurt > lastHurtTime && onHurt.isEnabled() && countsAsCombatDamage(mc.player.getRecentDamageSource())) {
            lastCombatMs = System.currentTimeMillis();
        }
        lastHurtTime = hurt;
        if (!mc.player.isAlive()) lastCombatMs = 0L;
    }

    private boolean countsAsCombatDamage(DamageSource source) {
        if (!playersOnly.isEnabled()) return true;
        if (source == null) return false;
        Entity attacker = source.getAttacker();
        return attacker instanceof PlayerEntity && attacker != mc.player;
    }

    @Override
    public void onVanillaEntityAttack(Entity target) {
        if (!onAttack.isEnabled() || !(target instanceof LivingEntity) || target == mc.player) return;
        if (playersOnly.isEnabled() && !(target instanceof PlayerEntity)) return;
        lastCombatMs = System.currentTimeMillis();
    }

    @Override
    public boolean blockHotbarScroll() {
        if (mc.player == null || mc.player.isSpectator()) return false;
        if (mode.is("Always")) return true;
        if (lastCombatMs <= 0L) return false;
        return System.currentTimeMillis() - lastCombatMs <= (long) (combatWindow.getValue() * 1000.0);
    }
}
