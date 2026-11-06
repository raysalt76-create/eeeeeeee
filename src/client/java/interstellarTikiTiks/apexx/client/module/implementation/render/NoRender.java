package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.HurtCamSuppression;
import interstellarTikiTiks.apexx.client.module.hooks.NoRenderProvider;
import net.minecraft.client.particle.BlockDustParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;

import java.util.concurrent.ThreadLocalRandom;

/** Hides selected vanilla overlays, weather, particles and animations on your screen only. */
public final class NoRender extends Module implements NoRenderProvider, HurtCamSuppression {
    private static final String OVERLAYS = "Overlays";
    private static final String WORLD = "World";
    private static final String PARTICLES = "Particles";

    private final BooleanSetting fire = new BooleanSetting("Fire", true)
            .setDescription("Removes the flames that cover your screen while you are burning.")
            .setSection(OVERLAYS);
    private final BooleanSetting pumpkin = new BooleanSetting("Pumpkin Blur", true)
            .setDescription("Removes the carved-pumpkin view (and any other helmet-slot screen overlay). Powder-snow frost stays.")
            .setSection(OVERLAYS);
    private final BooleanSetting underwater = new BooleanSetting("Underwater", false)
            .setDescription("Removes the dark water texture drawn over your screen while your head is underwater.")
            .setSection(OVERLAYS);
    private final BooleanSetting inWall = new BooleanSetting("In Wall", true)
            .setDescription("Removes the block texture that fills your screen when your head is inside a block.")
            .setSection(OVERLAYS);
    private final BooleanSetting totemAnimation = new BooleanSetting("Totem Animation", false)
            .setDescription("Hides the big totem that flies across your screen when your own totem pops. The pop "
                    + "itself, its particles and sound are unaffected.")
            .setSection(OVERLAYS);
    private final BooleanSetting hurtTilt = new BooleanSetting("Hurt Tilt", false)
            .setDescription("Stops the camera shaking and tilting when you take damage, so your view stays steady while you are being hit (formerly the No Hurt Cam module).")
            .setSection(OVERLAYS);

    private final BooleanSetting weather = new BooleanSetting("Weather", false)
            .setDescription("Hides falling rain and snow and the rain splash particles and sounds. The sky still darkens.")
            .setSection(WORLD);

    private final BooleanSetting explosions = new BooleanSetting("Explosions", false)
            .setDescription("Hides explosion puffs and the large explosion burst (crystals, anchors, TNT, creepers).")
            .setSection(PARTICLES);
    private final BooleanSetting totemParticles = new BooleanSetting("Totem Particles", false)
            .setDescription("Hides the yellow-green totem-of-undying particle burst.")
            .setSection(PARTICLES);
    private final BooleanSetting blockParticles = new BooleanSetting("Block Particles", false)
            .setDescription("Hides block-breaking debris and the block dust kicked up by running and landing.")
            .setSection(PARTICLES);
    private final BooleanSetting crits = new BooleanSetting("Crit Particles", false)
            .setDescription("Hides critical-hit and sharpness (enchanted hit) sparks.")
            .setSection(PARTICLES);
    private final BooleanSetting potions = new BooleanSetting("Potion Swirls", false)
            .setDescription("Hides the swirling effect particles around players with potion effects and splash bursts.")
            .setSection(PARTICLES);
    private final BooleanSetting campfire = new BooleanSetting("Campfire Smoke", false)
            .setDescription("Hides the tall smoke columns rising from campfires.")
            .setSection(PARTICLES);
    private final BooleanSetting fireworks = new BooleanSetting("Fireworks", false)
            .setDescription("Hides firework sparks and flashes, including elytra rocket trails.")
            .setSection(PARTICLES);
    private final SliderSetting density = new SliderSetting("Particle Density", 100, 0, 100, 0)
            .setDescription("Percentage of all remaining particles that are still shown. Lower it to cut particle "
                    + "clutter and frame drops; 100 shows every particle.")
            .setSection(PARTICLES);

    public NoRender() {
        super("NoRender", "Hides vanilla visual clutter on your screen: fire, pumpkin and in-wall overlays, underwater "
                + "tint, weather, the totem animation, the hurt camera tilt and selected particle types. Visible only to you.",
                Category.RENDER);
        addSetting(fire);
        addSetting(pumpkin);
        addSetting(underwater);
        addSetting(inWall);
        addSetting(totemAnimation);
        addSetting(hurtTilt);
        addSetting(weather);
        addSetting(explosions);
        addSetting(totemParticles);
        addSetting(blockParticles);
        addSetting(crits);
        addSetting(potions);
        addSetting(campfire);
        addSetting(fireworks);
        addSetting(density);
    }

    @Override
    public boolean rendersInWorld() {
        return false;
    }

    @Override
    public boolean hideFireOverlay() {
        return fire.isEnabled();
    }

    @Override
    public boolean hidePumpkinOverlay() {
        return pumpkin.isEnabled();
    }

    @Override
    public boolean hideUnderwaterOverlay() {
        return underwater.isEnabled();
    }

    @Override
    public boolean hideInWallOverlay() {
        return inWall.isEnabled();
    }

    @Override
    public boolean hideWeather() {
        return weather.isEnabled();
    }

    @Override
    public boolean hideTotemAnimation() {
        return totemAnimation.isEnabled();
    }

    @Override
    public boolean suppressHurtCam() {
        return hurtTilt.isEnabled();
    }

    @Override
    public boolean blockParticle(ParticleEffect effect) {
        ParticleType<?> type = effect.getType();
        if (explosions.isEnabled() && (type == ParticleTypes.EXPLOSION || type == ParticleTypes.EXPLOSION_EMITTER)) return true;
        if (totemParticles.isEnabled() && type == ParticleTypes.TOTEM_OF_UNDYING) return true;
        if (blockParticles.isEnabled() && (type == ParticleTypes.BLOCK || type == ParticleTypes.BLOCK_CRUMBLE)) return true;
        if (crits.isEnabled() && (type == ParticleTypes.CRIT || type == ParticleTypes.ENCHANTED_HIT)) return true;
        if (potions.isEnabled() && (type == ParticleTypes.ENTITY_EFFECT || type == ParticleTypes.EFFECT
                || type == ParticleTypes.INSTANT_EFFECT)) return true;
        if (campfire.isEnabled() && (type == ParticleTypes.CAMPFIRE_COSY_SMOKE
                || type == ParticleTypes.CAMPFIRE_SIGNAL_SMOKE)) return true;
        if (fireworks.isEnabled() && (type == ParticleTypes.FIREWORK || type == ParticleTypes.FLASH)) return true;
        double keep = density.getValue();
        return keep < 100.0 && ThreadLocalRandom.current().nextDouble(100.0) >= keep;
    }

    @Override
    public boolean blockParticle(Particle particle) {
        // Block-break debris is built directly as BlockDustParticle and never passes through an effect.
        return blockParticles.isEnabled() && particle instanceof BlockDustParticle;
    }
}
