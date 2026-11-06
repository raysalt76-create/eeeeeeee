package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackExecutionAware;
import interstellarTikiTiks.apexx.client.module.hooks.EntityStatusAware;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plays a local effect when something you recently hit dies. A kill is credited when the entity's
 * death status (or zero health) arrives within the window after your last accepted hit on it; friends
 * never trigger it. Everything is client-side and only you see or hear it.
 */
public final class KillEffects extends Module implements AttackExecutionAware, EntityStatusAware {
    private static final String EFFECT = "Effect";
    private static final String TARGETS = "Targets";
    private static final byte STATUS_DEATH = 3;
    private static final int MAX_TRACKED = 64;

    private final ModeSetting effect = new ModeSetting("Effect", "Lightning", "Lightning", "Totem Burst", "Flames",
            "Hearts", "Explosion", "Souls")
            .setDescription("Lightning: a harmless client-side lightning bolt (with its own thunder). Totem Burst: a "
                    + "totem-pop particle burst. Flames, Hearts, Explosion, Souls: a burst of that particle.")
            .setSection(EFFECT);
    private final SliderSetting amount = new SliderSetting("Amount", 1.0, 0.25, 3.0, 2)
            .setDescription("Particle count multiplier for the particle effects.")
            .setDependsOn("Effect:Totem Burst|Flames|Hearts|Explosion|Souls")
            .setSection(EFFECT);
    private final ModeSetting sound = new ModeSetting("Sound", "None", "None", "Level Up", "Anvil", "Bell", "Ding",
            "Thunder")
            .setDescription("Extra sound played at the kill. Lightning already makes its own thunder.")
            .setSection(EFFECT);
    private final SliderSetting volume = new SliderSetting("Volume", 1.0, 0.1, 2.0, 2)
            .setDescription("Loudness of the extra kill sound.")
            .setDependsOn("Sound:Level Up|Anvil|Bell|Ding|Thunder")
            .setSection(EFFECT);

    private final SliderSetting window = new SliderSetting("Credit Window", 3.0, 0.5, 10.0, 1)
            .setDescription("Seconds after your last hit on a target during which its death still counts as your kill.")
            .setSection(TARGETS);
    private final BooleanSetting playersOnly = new BooleanSetting("Players Only", true)
            .setDescription("Only player kills trigger the effect; off also includes mobs and animals.")
            .setSection(TARGETS);

    /** entity id -> last accepted hit time (ms). Client thread only. */
    private final Map<Integer, Long> recentHits = new HashMap<>();
    private ClientWorld lastWorld;
    private int nextBoltId = -1_000_000;

    public KillEffects() {
        super("KillEffects", "Plays an effect where a target you recently hit dies: a harmless lightning bolt or a "
                + "particle burst, with an optional sound. Friends never trigger it. Visible and audible only to you.",
                Category.RENDER);
        addSetting(effect);
        addSetting(amount);
        addSetting(sound);
        addSetting(volume);
        addSetting(window);
        addSetting(playersOnly);
    }

    @Override
    public boolean rendersInWorld() {
        return false;
    }

    @Override
    public void onDisable() {
        clear();
    }

    @Override
    protected void onRuntimeStateReleased() {
        clear();
    }

    private void clear() {
        recentHits.clear();
        lastWorld = null;
    }

    @Override
    public void onVanillaEntityAttack(Entity target, Vec3d hitPosition) {
        if (!(target instanceof LivingEntity living) || mc.world == null) return;
        // Hitting a corpse during its death animation must not credit a second kill.
        if (living.isDead() || living.getHealth() <= 0.0f || !eligible(living)) return;
        syncWorld();
        recentHits.put(living.getId(), System.currentTimeMillis());
        if (recentHits.size() > MAX_TRACKED) prune(System.currentTimeMillis());
    }

    @Override
    public void onEntityStatus(Entity entity, byte status) {
        if (status != STATUS_DEATH || !(entity instanceof LivingEntity living)) return;
        syncWorld();
        tryCredit(living);
    }

    /** Fallback for servers that remove dead players without a death status: zero health counts too. */
    @Override
    public void onTick() {
        if (mc.world == null) {
            recentHits.clear();
            return;
        }
        syncWorld();
        if (recentHits.isEmpty()) return;
        long now = System.currentTimeMillis();
        prune(now);
        Iterator<Map.Entry<Integer, Long>> it = recentHits.entrySet().iterator();
        while (it.hasNext()) {
            Entity e = mc.world.getEntityById(it.next().getKey());
            if (e instanceof LivingEntity living && (living.isDead() || living.getHealth() <= 0.0f)) {
                it.remove();
                if (eligible(living)) play(living);
            }
        }
    }

    private void tryCredit(LivingEntity living) {
        Long hitAt = recentHits.remove(living.getId());
        if (hitAt == null) return;
        if (System.currentTimeMillis() - hitAt > windowMs()) return;
        if (!eligible(living)) return;
        play(living);
    }

    private boolean eligible(LivingEntity living) {
        if (mc.player == null || living == mc.player) return false;
        if (playersOnly.isEnabled() && !(living instanceof PlayerEntity)) return false;
        return !(living instanceof PlayerEntity player && FriendManager.getInstance().isFriend(player.getUuid()));
    }

    private long windowMs() {
        return Math.round(window.getValue() * 1000.0);
    }

    private void prune(long now) {
        long limit = windowMs();
        recentHits.values().removeIf(t -> now - t > limit);
    }

    private void syncWorld() {
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            recentHits.clear();
        }
    }

    private void play(LivingEntity victim) {
        ClientWorld world = mc.world;
        if (world == null) return;
        Box box = victim.getBoundingBox();
        Vec3d base = new Vec3d(victim.getX(), victim.getY(), victim.getZ());
        double height = Math.max(0.5, box.getLengthY());
        float mult = (float) amount.getValue();

        switch (effect.getCurrentMode()) {
            case "Totem Burst" -> mc.particleManager.addEmitter(victim, ParticleTypes.TOTEM_OF_UNDYING,
                    Math.max(1, Math.round(30 * mult)));
            case "Flames" -> burst(world, ParticleTypes.FLAME, base, height, Math.round(40 * mult), 0.12);
            case "Hearts" -> burst(world, ParticleTypes.HEART, base, height, Math.round(14 * mult), 0.05);
            case "Explosion" -> {
                world.addParticleClient(ParticleTypes.EXPLOSION_EMITTER, base.x, base.y + height * 0.5, base.z, 0, 0, 0);
                burst(world, ParticleTypes.POOF, base, height, Math.round(20 * mult), 0.08);
            }
            case "Souls" -> burst(world, ParticleTypes.SOUL, base, height, Math.round(24 * mult), 0.06);
            default -> spawnBolt(world, base);
        }

        SoundEvent extra = switch (sound.getCurrentMode()) {
            case "Level Up" -> SoundEvents.ENTITY_PLAYER_LEVELUP;
            case "Anvil" -> SoundEvents.BLOCK_ANVIL_LAND;
            case "Bell" -> SoundEvents.BLOCK_BELL_USE;
            case "Ding" -> SoundEvents.BLOCK_NOTE_BLOCK_PLING.value();
            case "Thunder" -> SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER;
            default -> null;
        };
        if (extra != null) {
            world.playSoundClient(base.x, base.y, base.z, extra, SoundCategory.PLAYERS, (float) volume.getValue(),
                    1.0f, false);
        }
    }

    private static void burst(ClientWorld world, ParticleEffect type, Vec3d base, double height, int count, double speed) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int n = Math.max(1, Math.min(400, count));
        for (int i = 0; i < n; i++) {
            double x = base.x + r.nextDouble(-0.4, 0.4);
            double y = base.y + r.nextDouble(0.0, height);
            double z = base.z + r.nextDouble(-0.4, 0.4);
            world.addParticleClient(type, x, y, z, r.nextDouble(-speed, speed), r.nextDouble(0.0, speed * 1.5),
                    r.nextDouble(-speed, speed));
        }
    }

    /**
     * A cosmetic client-only bolt. It gets a negative entity id so it can never collide with a server
     * entity id, and vanilla's own lightning tick removes it after a few ticks.
     */
    private void spawnBolt(ClientWorld world, Vec3d at) {
        LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, world);
        bolt.setCosmetic(true);
        bolt.refreshPositionAfterTeleport(at.x, at.y, at.z);
        bolt.setId(nextBoltId--);
        if (nextBoltId < -2_000_000_000) nextBoltId = -1_000_000;
        world.addEntity(bolt);
    }
}
