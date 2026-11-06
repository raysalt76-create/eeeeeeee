package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackExecutionAware;
import interstellarTikiTiks.apexx.client.module.hooks.WorldSceneVisualProvider;
import interstellarTikiTiks.apexx.client.render.visuals.WorldSceneVisuals;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil;
import interstellarTikiTiks.apexx.client.util.render.ProceduralTextures;
import interstellarTikiTiks.apexx.client.util.render.ProceduralTextures.Blend;
import interstellarTikiTiks.apexx.client.util.render.VisualGeometry;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Physically styled hit effects driven only by accepted vanilla entity attacks.
 * Features a realistic 3D weapon cutting arc (Slash mode) with physical momentum-conserving
 * spatter and surface incision flash, plus a unified composite impact burst (Particles mode).
 */
public final class HitFx extends Module implements AttackExecutionAware, WorldSceneVisualProvider {
    private static final int G_SMOKE = 0, G_MIST = 1, G_STREAK = 2, G_SPARK = 3, G_GLOW = 4;
    private static final int MAX_PARTICLES = 900;
    private static final int MAX_SLASHES = 12;
    private static final int SLASH_SEGMENTS = 24;
    private static final int FULL_BRIGHT = 0xF000F0;

    private final ModeSetting mode = new ModeSetting("Mode", "Slash", "Slash", "Particles")
            .setDescription("Slash: realistic directional cutting arc, blade incision, and momentum-driven spatter. "
                    + "Particles: physical impact burst combining sparks, dust, vapor, embers, and shockwave.");
    private final ModeSetting particleStyle = new ModeSetting("Particle Style", "Composite",
            "Composite", "Sparks", "Dust & Vapor", "Blood", "Embers", "Shockwave")
            .setDescription("Particle style used in Particles mode. Composite combines all elements.")
            .setDependsOn("Mode:Particles");
    private final SliderSetting intensity = new SliderSetting("Intensity", 1.0, 0.25, 2.5, 2)
            .setDescription("How many particles each hit produces; higher is denser but costs more frames.");
    private final SliderSetting size = new SliderSetting("Size", 1.0, 0.5, 2.0, 2)
            .setDescription("Size of the slash arc and particles.");
    private final SliderSetting lifetime = new SliderSetting("Lifetime", 1.0, 0.5, 2.0, 2)
            .setDescription("How long each hit effect lingers before fading.");
    private final ModeSetting colorMode = new ModeSetting("Color", "Natural", "Natural", "Theme Sync", "Custom")
            .setDescription("Natural uses steel and realistic particle colours; Theme Sync tints the effect with the ClickGUI accent; Custom uses the colour below.");
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(210, 225, 240))
            .setDescription("Used when Color is set to Custom.")
            .setDependsOn("Color:Custom");
    private final BooleanSetting critBoost = new BooleanSetting("Crit Boost", true)
            .setDescription("Makes critical hits stand out with a steep overhead cleave and heavier spatter.");
    private final BooleanSetting worldLighting = new BooleanSetting("World Lighting", true)
            .setDescription("Lights smoke, mist, dust and droplets by the light level where they are, so effects look darker in caves and at night.");
    private final BooleanSetting airDistortion = new BooleanSetting("Air Distortion", false)
            .setDescription("Adds a brief heat-shimmer ripple at the impact point.");
    private final BooleanSetting hitMarker = new BooleanSetting("Hit Marker", false)
            .setDescription("Flashes a small cross on your crosshair each time a hit lands.");

    private final List<SlashInstance> slashes = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<ShockDisc> discs = new ArrayList<>();
    private final Random random = new Random();
    private int swingCounter;
    private long lastFrameNanos = -1L;
    private float simTime;
    private ClientWorld lastWorld;
    private long markerAt = -1L;
    private long distortAt = -1L;
    private Vec3d distortPos;

    public HitFx() {
        super("HitFx", "Plays a visual effect at the target each time your hit lands: a directional blade slash with spatter, or a configurable particle burst, scaled up for critical hits. Visible only to you.", Category.RENDER);
        addSetting(mode);
        addSetting(particleStyle);
        addSetting(intensity);
        addSetting(size);
        addSetting(lifetime);
        addSetting(colorMode);
        addSetting(customColor);
        addSetting(critBoost);
        addSetting(worldLighting);
        addSetting(airDistortion);
        addSetting(hitMarker);
        mode.onChange(this::clearState);
        particleStyle.onChange(particles::clear);
    }

    @Override
    public void onDisable() {
        clearState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        clearState();
    }

    private void clearState() {
        slashes.clear();
        particles.clear();
        discs.clear();
        lastFrameNanos = -1L;
        lastWorld = null;
        markerAt = -1L;
        distortAt = -1L;
        distortPos = null;
    }

    @Override
    public void onVanillaEntityAttack(Entity target, Vec3d position) {
        if (target == null || position == null || mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();
        markerAt = now;

        Vec3d eye = mc.player.getEyePos();
        Vec3d incoming = safeNormalize(position.subtract(eye), mc.player.getRotationVec(1.0f));
        Vec3d outward = safeNormalize(position.subtract(target.getBoundingBox().getCenter()), incoming.negate());
        Vec3d ejecta = safeNormalize(outward.multiply(0.65).subtract(incoming.multiply(0.55)), outward);
        Vec3d carry = target.getVelocity().multiply(20.0 * 0.35);
        boolean crit = critBoost.isEnabled() && critReadyBeforeAttack;
        float count = (float) intensity.getValue() * (crit ? 1.6f : 1.0f);
        float speed = crit ? 1.25f : 1.0f;

        if (mode.is("Slash")) {
            emitSlash(position, incoming, ejecta, carry, count, speed, crit);
        } else {
            emitParticles(position, ejecta, incoming, carry, count, speed);
        }
        if (airDistortion.isEnabled()) {
            distortAt = now;
            distortPos = position;
        }

        while (particles.size() > MAX_PARTICLES) particles.remove(0);
    }

    private boolean critReadyBeforeAttack;

    /** Snapshot taken each tick, because sprint is already cleared and the cooldown reset when the attack hook fires. */
    @Override
    public void onTick() {
        var p = mc.player;
        critReadyBeforeAttack = p != null && p.getAttackCooldownProgress(0.5f) > 0.9f
                && p.fallDistance > 0.0f && !p.isOnGround() && !p.isClimbing() && !p.isTouchingWater()
                && !p.hasStatusEffect(StatusEffects.BLINDNESS) && !p.hasVehicle() && !p.isSprinting();
    }

    // ---- Slash Mode: realistic cutting arc & momentum-conserving spatter --------------------------------

    private void emitSlash(Vec3d at, Vec3d incoming, Vec3d ejecta, Vec3d carry, float count, float speed, boolean crit) {
        ItemStack held = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandStack();
        boolean sword = CombatItemUtil.isSword(held);
        boolean axe = CombatItemUtil.isAxe(held);
        boolean mace = CombatItemUtil.isMace(held);

        int swing = swingCounter++;
        double angleDeg;
        if (crit) {
            angleDeg = -82.0 + rand(-4.0, 4.0);
        } else if (axe) {
            angleDeg = (swing % 2 == 0 ? -75.0 : -105.0) + rand(-5.0, 5.0);
        } else if (mace) {
            angleDeg = -90.0 + rand(-3.0, 3.0);
        } else if (sword) {
            angleDeg = switch (swing % 3) {
                case 0 -> -38.0 + rand(-4.0, 4.0);
                case 1 -> 36.0 + rand(-4.0, 4.0);
                default -> -10.0 + rand(-4.0, 4.0);
            };
        } else {
            angleDeg = (swing % 2 == 0 ? -28.0 : 28.0) + rand(-5.0, 5.0);
        }
        double angleRad = Math.toRadians(angleDeg);

        Camera camera = mc.gameRenderer.getCamera();
        Quaternionf camRot = camera.getRotation();
        Vec3d camUp = toVec3d(new Vector3f(0f, 1f, 0f).rotate(camRot));
        Vec3d camLeft = toVec3d(new Vector3f(-1f, 0f, 0f).rotate(camRot));
        Vec3d camRight = camLeft.negate();

        Vec3d d = incoming;
        Vec3d r = safeNormalize(d.crossProduct(camUp).negate(), camRight);
        Vec3d u = safeNormalize(r.crossProduct(d), camUp);

        Vec3d tSlash = safeNormalize(r.multiply(Math.cos(angleRad)).add(u.multiply(Math.sin(angleRad))), r);
        Vec3d nSlash = safeNormalize(r.multiply(-Math.sin(angleRad)).add(u.multiply(Math.cos(angleRad))), u);

        float sizeVal = (float) size.getValue();
        float radius = 0.95f * sizeVal * (crit ? 1.25f : (axe ? 1.15f : 1.0f));
        float arcSpan = (float) Math.toRadians(axe ? 135.0 : (crit ? 130.0 : 122.0));
        float maxWidth = 0.22f * sizeVal * (axe ? 1.35f : (crit ? 1.3f : 1.0f));
        float maxLife = 0.22f * (float) lifetime.getValue();

        Color coreColor;
        Color wakeColor;
        if (colorMode.is("Natural")) {
            if (crit) {
                coreColor = new Color(255, 240, 200);
                wakeColor = new Color(255, 185, 80);
            } else if (mace) {
                coreColor = new Color(255, 245, 220);
                wakeColor = new Color(220, 175, 110);
            } else if (axe) {
                coreColor = new Color(240, 245, 255);
                wakeColor = new Color(190, 210, 235);
            } else {
                coreColor = new Color(245, 250, 255);
                wakeColor = new Color(175, 215, 255);
            }
        } else {
            Color base = tint(new Color(245, 250, 255));
            coreColor = base;
            wakeColor = base;
        }

        SlashInstance mainSlash = new SlashInstance(at, tSlash, nSlash, d, radius, arcSpan, maxWidth, maxLife, coreColor, wakeColor);
        mainSlash.flareRot = (float) Math.atan2(tSlash.dotProduct(camUp), tSlash.dotProduct(camRight));
        slashes.add(mainSlash);
        // Contact shock: a small disc facing the blade that snaps outward.
        spawnDisc(at, incoming, 0.34f * sizeVal * (crit ? 1.4f : 1f), 0.20f, crit ? 1.0f : 0.8f, wakeColor);

        if (mace) {
            Vec3d crossT = nSlash;
            Vec3d crossN = tSlash.negate();
            SlashInstance crossSlash = new SlashInstance(at, crossT, crossN, d, radius * 0.8f, (float) Math.toRadians(85.0),
                    maxWidth * 0.75f, maxLife * 0.85f, coreColor, wakeColor);
            crossSlash.flareRot = mainSlash.flareRot + 1.5708f;
            slashes.add(crossSlash);
        }

        while (slashes.size() > MAX_SLASHES) slashes.remove(0);

        // Momentum-conserving spatter fan strictly aligned with the blade's cutting tangent
        int spatterCount = scaled(14, count);
        for (int i = 0; i < spatterCount; i++) {
            float uFrac = 0.32f + 0.36f * random.nextFloat();
            float phi = (uFrac - 0.5f) * arcSpan;
            Vec3d spawnPos = at.add(tSlash.multiply(radius * Math.sin(phi)))
                    .add(nSlash.multiply(radius * (Math.cos(phi) - 1.0)))
                    .add(d.multiply(0.12 * (1.0 - Math.cos(phi))));

            Vec3d tangent = safeNormalize(tSlash.multiply(Math.cos(phi)).subtract(nSlash.multiply(Math.sin(phi))), tSlash);
            Vec3d vTangent = tangent.multiply(rand(5.0, 11.0) * speed);
            Vec3d vNormal = ejecta.multiply(rand(1.2, 2.8) * speed);
            Vec3d vSpread = nSlash.multiply(rand(-0.5, 0.5));
            Vec3d vVel = vTangent.add(vNormal).add(vSpread).add(carry.multiply(0.5));

            Particle p = spawn(G_SPARK, spawnPos, vVel, coreColor, 1.0f);
            p.life = rand(0.35, 0.75);
            p.s0 = p.s1 = rand(0.024, 0.042);
            p.stretch = 0.045f;
            p.drag = 1.0f;
            p.grav = 12.0f;
            p.collide = true;
            p.bounce = 0.42f;
            p.cooling = true;
        }

        // Fine friction mist trailing the cut
        int mistCount = scaled(4, count);
        for (int i = 0; i < mistCount; i++) {
            float uFrac = 0.4f + 0.3f * random.nextFloat();
            float phi = (uFrac - 0.5f) * arcSpan;
            Vec3d spawnPos = at.add(tSlash.multiply(radius * Math.sin(phi)));
            Vec3d tangent = safeNormalize(tSlash.multiply(Math.cos(phi)).subtract(nSlash.multiply(Math.sin(phi))), tSlash);
            Particle p = spawn(G_MIST, spawnPos, tangent.multiply(rand(1.5, 3.5) * speed).add(ejecta.multiply(0.5)), wakeColor, rand(0.35, 0.55));
            p.life = rand(0.22, 0.38);
            p.s0 = 0.08f;
            p.s1 = rand(0.35, 0.55);
            p.drag = 5.5f;
            p.lit = true;
        }
    }

    // ---- Particles Mode: composite impact burst & legacy presets ---------------------------------------

    private void emitParticles(Vec3d position, Vec3d ejecta, Vec3d incoming, Vec3d carry, float count, float speed) {
        switch (particleStyle.getCurrentMode()) {
            case "Sparks" -> emitSparks(position, ejecta, incoming, count, speed);
            case "Dust & Vapor" -> emitDustAndVapor(position, ejecta, incoming, carry, count, speed);
            case "Blood" -> emitBlood(position, ejecta, incoming, carry, count, speed);
            case "Embers" -> emitEmbers(position, ejecta, count, speed);
            case "Shockwave" -> emitShockwave(position, incoming, carry, count, speed);
            default -> emitComposite(position, ejecta, incoming, carry, count, speed);
        }
    }

    private void emitComposite(Vec3d at, Vec3d ejecta, Vec3d incoming, Vec3d carry, float count, float speed) {
        emitSparks(at, ejecta, incoming, count * 0.7f, speed);
        emitVapor(at, ejecta, incoming, carry, count * 0.6f, speed);
        emitGrit(at, ejecta, count * 0.5f, speed);
        emitEmbers(at, ejecta, count * 0.4f, speed);
        emitShockwaveRing(at, incoming, carry, count * 0.5f, speed);
    }

    private void emitDustAndVapor(Vec3d at, Vec3d ejecta, Vec3d incoming, Vec3d carry, float count, float speed) {
        emitVapor(at, ejecta, incoming, carry, count * 0.7f, speed);
        emitDustPuff(at, ejecta, carry, count * 0.7f, speed);
        emitGrit(at, ejecta, count * 0.8f, speed);
    }

    private void emitVapor(Vec3d at, Vec3d ejecta, Vec3d incoming, Vec3d carry, float count, float speed) {
        Color c = tint(new Color(236, 240, 245));
        for (int i = 0, n = scaled(16, count); i < n; i++) {
            Vec3d dir = cone(i % 3 == 0 ? incoming : ejecta, 0.75);
            Particle p = spawn(G_SMOKE, at, dir.multiply(rand(2.5, 6.5) * speed).add(carry), c, rand(0.22, 0.38));
            p.life = rand(0.35, 0.65); p.s0 = rand(0.06, 0.12); p.s1 = rand(0.45, 0.75);
            p.drag = 7.5f; p.buoy = 0.35f; p.turb = 1.4f; p.lit = true;
        }
        for (int i = 0, n = scaled(3, count); i < n; i++) {
            Particle p = spawn(G_MIST, at, cone(ejecta, 0.5).multiply(rand(1.0, 2.5) * speed).add(carry), c, rand(0.45, 0.65));
            p.life = rand(0.22, 0.38); p.s0 = 0.12f; p.s1 = rand(0.55, 0.8);
            p.drag = 6.0f; p.turb = 0.6f; p.lit = true;
        }
    }

    private void emitDustPuff(Vec3d at, Vec3d ejecta, Vec3d carry, float count, float speed) {
        Color c = tint(new Color(172, 156, 128));
        for (int i = 0, n = scaled(10, count); i < n; i++) {
            Particle p = spawn(G_SMOKE, at, cone(ejecta, 0.9).multiply(rand(1.2, 3.5) * speed).add(carry), c, rand(0.35, 0.55));
            p.life = rand(0.8, 1.4); p.s0 = rand(0.10, 0.18); p.s1 = rand(0.6, 0.95);
            p.drag = 4.0f; p.grav = 0.25f; p.turb = 0.7f; p.lit = true;
        }
    }

    private void emitGrit(Vec3d at, Vec3d ejecta, float count, float speed) {
        Color grit = tint(new Color(96, 84, 66));
        for (int i = 0, n = scaled(14, count); i < n; i++) {
            Particle p = spawn(G_STREAK, at, cone(ejecta, 0.8).multiply(rand(2.0, 5.5) * speed).add(0, 1.2, 0), grit, 0.95f);
            p.life = rand(0.6, 1.1); p.s0 = p.s1 = rand(0.02, 0.035); p.stretch = 0.035f;
            p.drag = 1.0f; p.grav = 14.0f; p.collide = true; p.bounce = 0.3f; p.lit = true;
        }
    }

    private void emitSparks(Vec3d at, Vec3d ejecta, Vec3d incoming, float count, float speed) {
        Color hot = colorMode.is("Natural") ? null : tint(Color.WHITE);
        for (int i = 0, n = scaled(18, count); i < n; i++) {
            Vec3d dir = cone(ejecta.subtract(incoming.multiply(0.2)), 1.1);
            Particle p = spawn(G_SPARK, at, dir.multiply(rand(4.0, 10.0) * speed).add(0, 1.0, 0), hot == null ? Color.WHITE : hot, 1.0f);
            p.life = rand(0.35, 0.9); p.s0 = p.s1 = rand(0.022, 0.04); p.stretch = 0.045f;
            p.drag = 0.8f; p.grav = 11.0f; p.collide = true; p.bounce = 0.45f; p.cooling = true;
        }
        Particle flash = spawn(G_GLOW, at, Vec3d.ZERO, hot == null ? new Color(255, 214, 160) : hot, 0.85f);
        flash.life = 0.07f; flash.s0 = 0.45f; flash.s1 = 0.2f;
        Color smoke = tint(new Color(92, 90, 88));
        for (int i = 0, n = scaled(3, count); i < n; i++) {
            Particle p = spawn(G_SMOKE, at, cone(ejecta, 0.6).multiply(rand(0.4, 1.0)), smoke, rand(0.18, 0.28));
            p.life = rand(0.7, 1.1); p.s0 = 0.06f; p.s1 = rand(0.3, 0.45);
            p.drag = 3.0f; p.buoy = 0.7f; p.turb = 1.0f; p.lit = true;
        }
    }

    private void emitBlood(Vec3d at, Vec3d ejecta, Vec3d incoming, Vec3d carry, float count, float speed) {
        Color drop = tint(new Color(122, 10, 12));
        for (int i = 0, n = scaled(16, count); i < n; i++) {
            Vec3d dir = cone(i % 4 == 0 ? incoming : ejecta, 0.7);
            Particle p = spawn(G_STREAK, at, dir.multiply(rand(2.0, 5.0) * speed).add(carry).add(0, 1.0, 0), drop, 0.95f);
            p.life = rand(0.6, 1.1); p.s0 = p.s1 = rand(0.03, 0.055); p.stretch = 0.04f;
            p.drag = 1.2f; p.grav = 15.0f; p.collide = true; p.bounce = 0.0f; p.lit = true;
        }
        Color mist = tint(new Color(150, 22, 24));
        for (int i = 0, n = scaled(5, count); i < n; i++) {
            Particle p = spawn(G_SMOKE, at, cone(ejecta, 0.8).multiply(rand(1.0, 2.5) * speed).add(carry), mist, rand(0.25, 0.4));
            p.life = rand(0.3, 0.5); p.s0 = 0.06f; p.s1 = rand(0.3, 0.45);
            p.drag = 6.5f; p.grav = 0.4f; p.turb = 0.8f; p.lit = true;
        }
    }

    private void emitShockwave(Vec3d at, Vec3d incoming, Vec3d carry, float count, float speed) {
        emitShockwaveRing(at, incoming, carry, count, speed);
        Color c = tint(new Color(232, 238, 246));
        Particle core = spawn(G_MIST, at, incoming.multiply(0.8), c, 0.5f);
        core.life = 0.25f; core.s0 = 0.15f; core.s1 = 0.8f; core.drag = 4.0f; core.lit = true;
    }

    private void emitShockwaveRing(Vec3d at, Vec3d incoming, Vec3d carry, float count, float speed) {
        Color c = tint(new Color(232, 238, 246));
        spawnDisc(at, incoming, 0.95f, 0.42f, Math.min(1f, 0.55f + count * 0.25f), c);
        spawnDisc(at, incoming, 0.55f, 0.28f, 0.55f, c);
        Vec3d u = safeNormalize(incoming.crossProduct(new Vec3d(0, 1, 0)), new Vec3d(1, 0, 0));
        Vec3d v = safeNormalize(incoming.crossProduct(u), new Vec3d(0, 1, 0));
        int n = scaled(10, count);
        double phase = random.nextDouble() * Math.PI * 2.0;
        for (int i = 0; i < n; i++) {
            double a = phase + (Math.PI * 2.0 * i) / n + rand(-0.15, 0.15);
            Vec3d radial = u.multiply(Math.cos(a)).add(v.multiply(Math.sin(a)));
            Particle p = spawn(G_SMOKE, at.add(radial.multiply(0.1)), radial.multiply(rand(2.4, 3.6) * speed).add(carry), c, rand(0.10, 0.18));
            p.life = rand(0.45, 0.7); p.s0 = 0.10f; p.s1 = rand(0.35, 0.55);
            p.drag = 4.5f; p.turb = 0.6f; p.lit = true;
        }
    }

    private void spawnDisc(Vec3d at, Vec3d normal, float maxRadius, float life, float strength, Color color) {
        Vec3d n = safeNormalize(normal, new Vec3d(0, 1, 0));
        Vec3d u = safeNormalize(n.crossProduct(Math.abs(n.y) < 0.95 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0)), new Vec3d(1, 0, 0));
        Vec3d v = safeNormalize(n.crossProduct(u), new Vec3d(0, 0, 1));
        ShockDisc d = new ShockDisc();
        d.pos = at; d.axisU = u; d.axisV = v;
        d.maxRadius = maxRadius; d.life = life; d.strength = strength; d.color = color;
        discs.add(d);
        while (discs.size() > 16) discs.remove(0);
    }

    private void emitEmbers(Vec3d at, Vec3d ejecta, float count, float speed) {
        Color hot = colorMode.is("Natural") ? Color.WHITE : tint(Color.WHITE);
        for (int i = 0, n = scaled(12, count); i < n; i++) {
            Particle p = spawn(G_GLOW, at, cone(ejecta, 1.0).multiply(rand(1.0, 3.2) * speed), hot, 1.0f);
            p.life = rand(0.8, 1.7); p.s0 = rand(0.05, 0.08); p.s1 = p.s0 * 0.4f;
            p.drag = 2.5f; p.buoy = 1.3f; p.turb = 1.8f; p.cooling = true; p.flicker = true;
        }
        Color smoke = tint(new Color(54, 51, 49));
        for (int i = 0, n = scaled(6, count); i < n; i++) {
            Particle p = spawn(G_SMOKE, at, cone(ejecta, 0.7).multiply(rand(0.6, 1.6)), smoke, rand(0.3, 0.45));
            p.life = rand(0.9, 1.5); p.s0 = 0.1f; p.s1 = rand(0.55, 0.8);
            p.drag = 3.0f; p.buoy = 0.9f; p.turb = 1.1f; p.lit = true;
        }
    }

    private Particle spawn(int group, Vec3d at, Vec3d velocity, Color color, float alpha) {
        Particle p = new Particle();
        p.group = group;
        p.x = at.x; p.y = at.y; p.z = at.z;
        p.vx = velocity.x; p.vy = velocity.y; p.vz = velocity.z;
        p.r = color.getRed() / 255f; p.g = color.getGreen() / 255f; p.b = color.getBlue() / 255f; p.a = alpha;
        p.rot = random.nextFloat() * MathHelper.TAU;
        p.rotVel = (random.nextFloat() - 0.5f) * 3.0f;
        p.sprite = random.nextInt(4);
        p.seed = random.nextFloat() * 100f;
        particles.add(p);
        return p;
    }

    // ---- simulation -------------------------------------------------------------------------------------

    private void simulate(float dt) {
        simTime += dt;
        float lifeScale = (float) lifetime.getValue();

        for (int i = slashes.size() - 1; i >= 0; i--) {
            SlashInstance s = slashes.get(i);
            s.age += dt;
            if (s.age >= s.life * lifeScale) {
                slashes.remove(i);
            }
        }

        for (int i = discs.size() - 1; i >= 0; i--) {
            ShockDisc d = discs.get(i);
            d.age += dt;
            if (d.age >= d.life * lifeScale) discs.remove(i);
        }

        BlockPos.Mutable cell = new BlockPos.Mutable();
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.age += dt;
            if (p.age >= p.life * lifeScale) { particles.remove(i); continue; }
            if (p.stuck) continue;

            if (p.turb > 0f) {
                float t = simTime, s = p.seed;
                p.vx += p.turb * (Math.sin(p.y * 2.3 + t * 1.7 + s) + Math.sin(p.z * 1.9 - t * 1.3 + s * 2)) * dt;
                p.vy += p.turb * 0.6 * Math.sin(p.x * 2.1 + t * 1.1 + s * 3) * dt;
                p.vz += p.turb * (Math.sin(p.x * 1.7 - t * 1.9 + s) + Math.sin(p.y * 2.7 + t * 0.9 + s)) * dt;
            }
            p.vy += (p.buoy - p.grav) * dt;
            double damp = Math.exp(-p.drag * dt);
            p.vx *= damp; p.vy *= damp; p.vz *= damp;
            double nx = p.x + p.vx * dt, ny = p.y + p.vy * dt, nz = p.z + p.vz * dt;
            if (p.collide && p.vy < 0) {
                double floor = floorAt(cell, nx, ny, nz);
                if (ny < floor) {
                    ny = floor + 0.01;
                    if (p.bounce <= 0f || -p.vy * p.bounce < 0.6) {
                        p.vx = p.vy = p.vz = 0;
                        p.stuck = p.bounce <= 0f;
                    } else {
                        p.vy = -p.vy * p.bounce; p.vx *= 0.55; p.vz *= 0.55;
                    }
                }
            }
            p.x = nx; p.y = ny; p.z = nz;
            p.rot += p.rotVel * dt;
            p.rotVel *= (float) Math.exp(-1.5 * dt);
        }
    }

    /** Top of the collision shape in the cell containing (x, y, z), or -inf when the cell is open. */
    private double floorAt(BlockPos.Mutable cell, double x, double y, double z) {
        cell.set(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));
        BlockState state = mc.world.getBlockState(cell);
        if (state.isAir()) return Double.NEGATIVE_INFINITY;
        VoxelShape shape = state.getCollisionShape(mc.world, cell);
        if (shape.isEmpty()) return Double.NEGATIVE_INFINITY;
        return cell.getY() + shape.getMax(Direction.Axis.Y);
    }

    // ---- rendering --------------------------------------------------------------------------------------

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || mc.world == null) {
            clearState();
            return;
        }
        if (mc.world != lastWorld) {
            clearState();
            lastWorld = mc.world;
        }
        long now = System.nanoTime();
        float dt = lastFrameNanos < 0 ? 0f : MathHelper.clamp((now - lastFrameNanos) / 1.0e9f, 0f, 0.05f);
        lastFrameNanos = now;

        if (slashes.isEmpty() && particles.isEmpty() && discs.isEmpty()) return;
        if (!ProceduralTextures.ensure()) return;
        simulate(dt);
        if (slashes.isEmpty() && particles.isEmpty() && discs.isEmpty()) return;

        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cam = camera.getCameraPos();
        Quaternionf camRot = camera.getRotation();
        Vec3d up = toVec3d(new Vector3f(0f, 1f, 0f).rotate(camRot));
        Vec3d left = toVec3d(new Vector3f(-1f, 0f, 0f).rotate(camRot));
        Vec3d right = left.negate();
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float lifeScale = (float) lifetime.getValue();
        float sizeScale = (float) size.getValue();

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(65536)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);

            // Pass 1: shock discs, then textured blade trails with their flare and incision flash
            if (!discs.isEmpty()) {
                VertexConsumer ringBuf = immediate.getBuffer(ProceduralTextures.layer(ProceduralTextures.RING, Blend.ADDITIVE, false));
                renderShockDiscs(ringBuf, matrix, cam, lifeScale, sizeScale);
                immediate.draw();
            }
            if (!slashes.isEmpty()) {
                VertexConsumer blade = immediate.getBuffer(ProceduralTextures.layer(ProceduralTextures.SLASH, Blend.ADDITIVE, false));
                renderSlashBlades(blade, matrix, cam, lifeScale, sizeScale);
                immediate.draw();
                VertexConsumer flareBuf = immediate.getBuffer(ProceduralTextures.layer(ProceduralTextures.BOLT, Blend.ADDITIVE, false));
                renderSlashFlares(flareBuf, matrix, cam, right, up, lifeScale, sizeScale);
                immediate.draw();
                VertexConsumer glowBuf = immediate.getBuffer(ProceduralTextures.layer(ProceduralTextures.GLOW, Blend.ADDITIVE, false));
                renderSlashGlints(glowBuf, matrix, cam, right, up, lifeScale, sizeScale);
                immediate.draw();
            }

            // Pass 2: Physical particles (spatter, sparks, streaks, smoke, mist)
            if (!particles.isEmpty()) {
                boolean useLight = worldLighting.isEnabled();
                BlockPos.Mutable cell = new BlockPos.Mutable();

                List<Particle> ordered = new ArrayList<>(particles);
                for (Particle p : ordered) {
                    p.depth = (p.x - cam.x) * (p.x - cam.x) + (p.y - cam.y) * (p.y - cam.y) + (p.z - cam.z) * (p.z - cam.z);
                }
                ordered.sort(Comparator.comparingDouble((Particle p) -> -p.depth));

                int[] order = {G_SMOKE, G_MIST, G_STREAK, G_SPARK, G_GLOW};
                for (int group : order) {
                    VertexConsumer buffer = null;
                    for (Particle p : ordered) {
                        if (p.group != group) continue;
                        float t = MathHelper.clamp(p.age / (p.life * lifeScale), 0f, 1f);
                        float alpha = p.a * fade(p, t);
                        if (alpha <= 0.004f) continue;
                        if (buffer == null) buffer = immediate.getBuffer(layerFor(group));
                        float r = p.r, g = p.g, b = p.b;
                        if (p.cooling) {
                            float[] bb = blackbody(t);
                            r *= bb[0]; g *= bb[1]; b *= bb[2];
                        }
                        int light = p.lit && useLight ? WorldRenderer.getLightmapCoordinates(mc.world, cell.set(p.x, p.y, p.z)) : FULL_BRIGHT;
                        int color = argb(alpha, r, g, b);
                        float s = (p.s0 + (p.s1 - p.s0) * easeOut(t)) * sizeScale;
                        Vec3d center = new Vec3d(p.x - cam.x, p.y - cam.y, p.z - cam.z);
                        if (group == G_STREAK || group == G_SPARK) {
                            streak(buffer, matrix, center, p, s, left, color, light);
                        } else {
                            float u0 = 0f, v0 = 0f, u1 = 1f, v1 = 1f;
                            if (group == G_SMOKE) {
                                u0 = (p.sprite & 1) * 0.5f; v0 = (p.sprite >> 1) * 0.5f; u1 = u0 + 0.5f; v1 = v0 + 0.5f;
                            }
                            billboard(buffer, matrix, center, left, up, p.rot, s, u0, v0, u1, v1, color, light);
                        }
                    }
                    if (buffer != null) immediate.draw();
                }
            }
        }
    }

    private Vec3d slashPoint(SlashInstance slash, float u) {
        float phi = (u - 0.5f) * slash.arcSpan;
        return slash.hitPos
                .add(slash.tangent.multiply(slash.radius * Math.sin(phi)))
                .add(slash.normal.multiply(slash.radius * (Math.cos(phi) - 1.0)))
                .add(slash.depth.multiply(0.14 * (1.0 - Math.cos(phi))));
    }

    /** Head/tail window of the trail: the blade sweeps the arc, then the tail catches up and the trail fades. */
    private static float[] slashWindow(float p) {
        float head, tail;
        if (p < 0.28f) {
            head = Math.min(1.0f, p / 0.28f);
            tail = 0.0f;
        } else {
            head = 1.0f;
            tail = (p - 0.28f) / 0.72f;
        }
        return new float[]{head, tail};
    }

    private void renderSlashBlades(VertexConsumer buffer, Matrix4f matrix, Vec3d cam, float lifeScale, float sizeScale) {
        final int segs = SLASH_SEGMENTS * 2;
        Vec3d[] outer = new Vec3d[segs + 1];
        Vec3d[] inner = new Vec3d[segs + 1];
        float[] alphas = new float[segs + 1];

        for (SlashInstance slash : slashes) {
            float p = MathHelper.clamp(slash.age / (slash.life * lifeScale), 0f, 1f);
            if (p >= 1f) continue;
            float[] win = slashWindow(p);
            float envelope = (float) Math.pow(1.0f - p, 1.5);
            if (envelope <= 0.005f) continue;

            Vec3d arcCenter = slash.hitPos.subtract(slash.normal.multiply(slash.radius));
            for (int i = 0; i <= segs; i++) {
                float u = (float) i / segs;
                float phi = (u - 0.5f) * slash.arcSpan;
                Vec3d world = slashPoint(slash, u);
                Vec3d posCam = world.subtract(cam);
                Vec3d tan = safeNormalize(slash.tangent.multiply(Math.cos(phi)).subtract(slash.normal.multiply(Math.sin(phi))), slash.tangent);
                Vec3d widthDir = safeNormalize(posCam.negate().crossProduct(tan), slash.normal);
                // Keep the razor edge on the convex side of the arc regardless of view angle.
                if (widthDir.dotProduct(world.subtract(arcCenter)) < 0) widthDir = widthDir.negate();

                // Crescent: pointed at both ends, fullest just behind the middle.
                float profile = (float) Math.pow(Math.sin(Math.PI * Math.pow(u, 0.82)), 1.15);
                float w = slash.maxRibbonWidth * sizeScale * profile * 1.25f;
                outer[i] = posCam.add(widthDir.multiply(w * 0.18));
                inner[i] = posCam.subtract(widthDir.multiply(w * 0.82));

                if (u < win[1] || u > win[0]) {
                    alphas[i] = 0f;
                } else {
                    float local = (u - win[1]) / Math.max(0.001f, win[0] - win[1]);
                    // Brightest at the moving head, long soft tail behind it.
                    alphas[i] = (float) Math.pow(local, 1.35) * envelope;
                }
            }
            for (int i = 0; i < segs; i++) {
                float a0 = alphas[i], a1 = alphas[i + 1];
                if (a0 <= 0.002f && a1 <= 0.002f) continue;
                int cR0 = VisualGeometry.color(slash.coreColor, Math.min(1f, a0 * 1.1f));
                int cR1 = VisualGeometry.color(slash.coreColor, Math.min(1f, a1 * 1.1f));
                int cT0 = VisualGeometry.color(slash.wakeColor, Math.min(1f, a0));
                int cT1 = VisualGeometry.color(slash.wakeColor, Math.min(1f, a1));
                float u0 = (float) i / segs, u1 = (float) (i + 1) / segs;
                VisualGeometry.quad(buffer, matrix,
                        outer[i].x, outer[i].y, outer[i].z, outer[i + 1].x, outer[i + 1].y, outer[i + 1].z,
                        inner[i + 1].x, inner[i + 1].y, inner[i + 1].z, inner[i].x, inner[i].y, inner[i].z,
                        u0, 0f, u1, 1f, cR0, cR1, cT1, cT0, FULL_BRIGHT);
            }
        }
    }

    /** Anamorphic streak flare through the contact point, along the cut and across it. */
    private void renderSlashFlares(VertexConsumer buffer, Matrix4f matrix, Vec3d cam, Vec3d right, Vec3d up, float lifeScale, float sizeScale) {
        for (SlashInstance slash : slashes) {
            float p = MathHelper.clamp(slash.age / (slash.life * lifeScale), 0f, 1f);
            float k = p / 0.34f;
            if (k >= 1f) continue;
            float strength = (1f - k) * (1f - k);
            Vec3d hit = slash.hitPos.subtract(cam);
            float len = 0.78f * sizeScale * (1.0f - 0.4f * k);
            int main = VisualGeometry.color(slash.coreColor, strength * 0.95f);
            int cross = VisualGeometry.color(slash.wakeColor, strength * 0.45f);
            VisualGeometry.sprite(buffer, matrix, hit.x, hit.y, hit.z, right.x, right.y, right.z, up.x, up.y, up.z,
                    len, 0.035 * sizeScale, slash.flareRot, 0f, 0f, 1f, 1f, main, FULL_BRIGHT);
            VisualGeometry.sprite(buffer, matrix, hit.x, hit.y, hit.z, right.x, right.y, right.z, up.x, up.y, up.z,
                    len * 0.42, 0.022 * sizeScale, slash.flareRot + 1.5708f, 0f, 0f, 1f, 1f, cross, FULL_BRIGHT);
        }
    }

    /** Short hot flash on contact plus a bead of light riding the blade tip. */
    private void renderSlashGlints(VertexConsumer buffer, Matrix4f matrix, Vec3d cam, Vec3d right, Vec3d up, float lifeScale, float sizeScale) {
        for (SlashInstance slash : slashes) {
            float p = MathHelper.clamp(slash.age / (slash.life * lifeScale), 0f, 1f);
            if (p < 0.3f) {
                float k = p / 0.3f;
                Vec3d hit = slash.hitPos.subtract(cam);
                float flash = (1f - k) * (1f - k);
                double size = 0.55 * sizeScale * (0.5 + 0.5 * k);
                VisualGeometry.sprite(buffer, matrix, hit.x, hit.y, hit.z, right.x, right.y, right.z, up.x, up.y, up.z,
                        size, size, 0f, 0f, 0f, 1f, 1f, VisualGeometry.color(slash.wakeColor, flash * 0.75f), FULL_BRIGHT);
                double core = 0.2 * sizeScale;
                VisualGeometry.sprite(buffer, matrix, hit.x, hit.y, hit.z, right.x, right.y, right.z, up.x, up.y, up.z,
                        core, core, 0f, 0f, 0f, 1f, 1f, VisualGeometry.color(Color.WHITE, flash), FULL_BRIGHT);
            }
            float[] win = slashWindow(p);
            if (p < 0.34f) {
                Vec3d tip = slashPoint(slash, MathHelper.clamp(win[0], 0f, 1f)).subtract(cam);
                float fade = 1f - p / 0.34f;
                double size = 0.30 * sizeScale;
                VisualGeometry.sprite(buffer, matrix, tip.x, tip.y, tip.z, right.x, right.y, right.z, up.x, up.y, up.z,
                        size, size, 0f, 0f, 0f, 1f, 1f, VisualGeometry.color(slash.coreColor, fade * 0.8f), FULL_BRIGHT);
            }
        }
    }

    private void renderShockDiscs(VertexConsumer buffer, Matrix4f matrix, Vec3d cam, float lifeScale, float sizeScale) {
        for (ShockDisc d : discs) {
            float t = MathHelper.clamp(d.age / (d.life * lifeScale), 0f, 1f);
            if (t >= 1f) continue;
            float radius = d.maxRadius * sizeScale * (1f - (float) Math.pow(1f - t, 3.2)) / 0.8f;
            float alpha = (float) Math.pow(1f - t, 1.6) * d.strength;
            if (radius < 0.01f || alpha < 0.004f) continue;
            Vec3d c = d.pos.subtract(cam);
            Vec3d a = d.axisU.multiply(radius), b = d.axisV.multiply(radius);
            int col = VisualGeometry.color(d.color, alpha);
            VisualGeometry.quad(buffer, matrix,
                    c.x - a.x - b.x, c.y - a.y - b.y, c.z - a.z - b.z,
                    c.x + a.x - b.x, c.y + a.y - b.y, c.z + a.z - b.z,
                    c.x + a.x + b.x, c.y + a.y + b.y, c.z + a.z + b.z,
                    c.x - a.x + b.x, c.y - a.y + b.y, c.z - a.z + b.z,
                    0f, 0f, 1f, 1f, col, col, col, col, FULL_BRIGHT);
        }
    }

    private static int colorWithAlpha(Color c, float a) {
        int alpha = MathHelper.clamp((int) (a * 255f), 0, 255);
        return (alpha << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    private static net.minecraft.client.render.RenderLayer layerFor(int group) {
        return switch (group) {
            case G_SMOKE -> ProceduralTextures.layer(ProceduralTextures.SMOKE, Blend.ALPHA, true);
            case G_MIST -> ProceduralTextures.layer(ProceduralTextures.SMOKE, Blend.ALPHA, true);
            case G_STREAK -> ProceduralTextures.layer(ProceduralTextures.STREAK, Blend.ALPHA, true);
            case G_SPARK -> ProceduralTextures.layer(ProceduralTextures.STREAK, Blend.ADDITIVE, true);
            default -> ProceduralTextures.layer(ProceduralTextures.GLOW, Blend.ADDITIVE, true);
        };
    }

    /** Short fade-in to avoid popping, then a thinning fade as the medium spreads out. */
    private float fade(Particle p, float t) {
        float in = MathHelper.clamp(t / 0.06f, 0f, 1f);
        float out;
        if (p.group == G_SPARK || p.cooling) out = 1f - t * t * t;
        else if (p.stuck) out = 1f - t;
        else out = (float) Math.pow(1f - t, 1.6f);
        if (p.flicker) out *= 0.7f + 0.3f * (float) Math.sin(simTime * 31f + p.seed * 7f);
        return in * out;
    }

    /** Approximate incandescent colour cooling from white-yellow through orange to dull red. */
    private static float[] blackbody(float t) {
        if (t < 0.25f) return mix(new float[]{1f, 0.97f, 0.86f}, new float[]{1f, 0.78f, 0.36f}, t / 0.25f);
        if (t < 0.65f) return mix(new float[]{1f, 0.78f, 0.36f}, new float[]{1f, 0.38f, 0.08f}, (t - 0.25f) / 0.4f);
        return mix(new float[]{1f, 0.38f, 0.08f}, new float[]{0.55f, 0.08f, 0.02f}, (t - 0.65f) / 0.35f);
    }

    private static float[] mix(float[] a, float[] b, float k) {
        return new float[]{a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k};
    }

    private void streak(VertexConsumer buffer, Matrix4f matrix, Vec3d center, Particle p, float width, Vec3d fallbackSide, int color, int light) {
        Vec3d vel = new Vec3d(p.vx, p.vy, p.vz);
        double speed = vel.length();
        Vec3d dir = speed < 1.0e-4 ? new Vec3d(0, -1, 0) : vel.multiply(1.0 / speed);
        double length = Math.max(width * 1.6, speed * p.stretch + width);
        Vec3d toCamera = safeNormalize(center.negate(), new Vec3d(0, 0, 1));
        Vec3d side = safeNormalize(dir.crossProduct(toCamera), fallbackSide).multiply(width * 0.5);
        Vec3d head = center.add(dir.multiply(length * 0.5));
        Vec3d tail = center.subtract(dir.multiply(length * 0.5));
        vertex(buffer, matrix, head.add(side), 1f, 0f, color, light);
        vertex(buffer, matrix, head.subtract(side), 1f, 1f, color, light);
        vertex(buffer, matrix, tail.subtract(side), 0f, 1f, color, light);
        vertex(buffer, matrix, head.add(side), 1f, 0f, color, light);
        vertex(buffer, matrix, tail.subtract(side), 0f, 1f, color, light);
        vertex(buffer, matrix, tail.add(side), 0f, 0f, color, light);
    }

    private static void billboard(VertexConsumer buffer, Matrix4f matrix, Vec3d center, Vec3d left, Vec3d up, float rotation,
                                  float size, float u0, float v0, float u1, float v1, int color, int light) {
        double half = size * 0.5, cos = Math.cos(rotation), sin = Math.sin(rotation);
        Vec3d ax = left.multiply(cos).add(up.multiply(-sin)).multiply(half);
        Vec3d ay = left.multiply(sin).add(up.multiply(cos)).multiply(half);
        quad(buffer, matrix, center.subtract(ax).add(ay), center.add(ax).add(ay), center.add(ax).subtract(ay), center.subtract(ax).subtract(ay),
                u0, v0, u1, v1, color, light);
    }

    private static void quad(VertexConsumer buffer, Matrix4f matrix, Vec3d a, Vec3d b, Vec3d c, Vec3d d,
                             float u0, float v0, float u1, float v1, int color, int light) {
        vertex(buffer, matrix, a, u0, v0, color, light);
        vertex(buffer, matrix, b, u1, v0, color, light);
        vertex(buffer, matrix, c, u1, v1, color, light);
        vertex(buffer, matrix, a, u0, v0, color, light);
        vertex(buffer, matrix, c, u1, v1, color, light);
        vertex(buffer, matrix, d, u0, v1, color, light);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, Vec3d pos, float u, float v, int color, int light) {
        VisualGeometry.vertex(buffer, matrix, pos.x, pos.y, pos.z, u, v, color, light);
    }

    // ---- world compositor (air distortion) and crosshair marker -----------------------------------------

    @Override
    public void contributeWorldSceneVisuals(WorldSceneVisuals frame, long nowMillis) {
        if (!isEnabled() || distortAt < 0L || distortPos == null) return;
        float age = (nowMillis - distortAt) / 1000.0f;
        float life = (mode.is("Slash") ? 0.24f : 0.32f) * (float) lifetime.getValue();
        if (age >= life) { distortAt = -1L; distortPos = null; return; }
        float envelope = (float) Math.pow(1.0f - age / life, 2.0);
        float radius = mode.is("Slash") ? 0.16f : 0.12f;
        float strength = (mode.is("Slash") ? 0.65f : 0.55f) * envelope;
        frame.setHitRipple(distortPos, radius, strength, age, new Color(200, 215, 230));
    }

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (!hitMarker.isEnabled() || markerAt < 0L) return;
        float progress = (System.currentTimeMillis() - markerAt) / 220f;
        if (progress >= 1f) { markerAt = -1L; return; }
        int alpha = (int) ((1f - progress) * 200f);
        int argb = (alpha << 24) | 0xF0F0F0;
        int cx = context.getScaledWindowWidth() / 2, cy = context.getScaledWindowHeight() / 2;
        int gap = 3 + Math.round(progress * 2f), len = 4;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int i = 0; i < len; i++) {
                    int px = cx + sx * (gap + i), py = cy + sy * (gap + i);
                    context.fill(px, py, px + 1, py + 1, argb);
                }
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private Color tint(Color natural) {
        Color tinted = switch (colorMode.getCurrentMode()) {
            case "Theme Sync" -> AccentState.accentColor();
            case "Custom" -> customColor.getValue();
            default -> null;
        };
        if (tinted == null) return natural;
        float lum = (natural.getRed() * 0.299f + natural.getGreen() * 0.587f + natural.getBlue() * 0.114f) / 255f;
        float tl = Math.max(0.05f, (tinted.getRed() * 0.299f + tinted.getGreen() * 0.587f + tinted.getBlue() * 0.114f) / 255f);
        float k = Math.min(lum / tl, 255f / Math.max(1, Math.max(tinted.getRed(), Math.max(tinted.getGreen(), tinted.getBlue()))));
        return new Color(MathHelper.clamp((int) (tinted.getRed() * k), 0, 255),
                MathHelper.clamp((int) (tinted.getGreen() * k), 0, 255),
                MathHelper.clamp((int) (tinted.getBlue() * k), 0, 255));
    }

    private int scaled(int base, float multiplier) {
        float n = base * multiplier;
        int whole = (int) n;
        return Math.max(1, whole + (random.nextFloat() < n - whole ? 1 : 0));
    }

    private float rand(double min, double max) {
        return (float) (min + random.nextDouble() * (max - min));
    }

    private Vec3d cone(Vec3d axis, double spread) {
        Vec3d offset = new Vec3d(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).multiply(spread * 0.6);
        return safeNormalize(axis.add(offset), axis);
    }

    private static float easeOut(float t) {
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    private static int argb(float a, float r, float g, float b) {
        return (MathHelper.clamp((int) (a * 255f), 0, 255) << 24) | (MathHelper.clamp((int) (r * 255f), 0, 255) << 16)
                | (MathHelper.clamp((int) (g * 255f), 0, 255) << 8) | MathHelper.clamp((int) (b * 255f), 0, 255);
    }

    private static Vec3d safeNormalize(Vec3d v, Vec3d fallback) {
        double len = v.length();
        return len < 1.0e-6 ? fallback : v.multiply(1.0 / len);
    }

    private static Vec3d toVec3d(Vector3f v) {
        return new Vec3d(v.x(), v.y(), v.z());
    }

    private static final class SlashInstance {
        final Vec3d hitPos;
        final Vec3d tangent;
        final Vec3d normal;
        final Vec3d depth;
        final float radius;
        final float arcSpan;
        final float maxRibbonWidth;
        final float life;
        final Color coreColor;
        final Color wakeColor;
        float flareRot;
        float age;

        SlashInstance(Vec3d hitPos, Vec3d tangent, Vec3d normal, Vec3d depth, float radius, float arcSpan,
                      float maxRibbonWidth, float life, Color coreColor, Color wakeColor) {
            this.hitPos = hitPos;
            this.tangent = tangent;
            this.normal = normal;
            this.depth = depth;
            this.radius = radius;
            this.arcSpan = arcSpan;
            this.maxRibbonWidth = maxRibbonWidth;
            this.life = life;
            this.coreColor = coreColor;
            this.wakeColor = wakeColor;
        }
    }

    private static final class ShockDisc {
        Vec3d pos, axisU, axisV;
        float maxRadius, life, strength, age;
        Color color;
    }

    private static final class Particle {
        int group, sprite;
        double x, y, z, vx, vy, vz, depth;
        float age, life = 0.5f, s0 = 0.1f, s1 = 0.3f;
        float r, g, b, a;
        float drag, grav, buoy, turb, bounce, stretch, rot, rotVel, seed;
        boolean collide, lit, stuck, cooling, flicker;
    }
}
