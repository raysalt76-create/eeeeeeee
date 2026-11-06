package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiThemeMaterial;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.WorldSceneVisualProvider;
import interstellarTikiTiks.apexx.client.render.msdf.Msdf;
import interstellarTikiTiks.apexx.client.render.msdf.MsdfFont;
import interstellarTikiTiks.apexx.client.render.msdf.WorldProjector;
import interstellarTikiTiks.apexx.client.mixin.GameRendererAccessor;
import interstellarTikiTiks.apexx.client.render.visuals.GpuWorldVisualEngine;
import interstellarTikiTiks.apexx.client.render.visuals.TrajectoryVisualRenderer;
import interstellarTikiTiks.apexx.client.render.visuals.TrajectoryVisualRenderer.Path;
import interstellarTikiTiks.apexx.client.render.visuals.WorldSceneVisuals;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.render.ThemeColors;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.SpectralArrowEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.item.*;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Arm;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Predicts held and in-flight projectile arcs. The flight model (gravity, drag, step order, block and entity
 * collision) is unchanged; this class decides how the result is presented, and hands geometry to
 * {@link TrajectoryVisualRenderer} and terrain-projected landing decals to {@link GpuWorldVisualEngine}.
 */
public class Trajectory extends Module implements WorldSceneVisualProvider {

    private static final List<Integer> ORDER_MDG = List.of(0, 1, 2);
    private static final List<Integer> ORDER_GMD = List.of(2, 0, 1);
    private static final List<Integer> ORDER_GDM = List.of(2, 1, 0);

    private static final long DECAL_HEARTBEAT_NANOS = 400_000_000L;

    // Projectile type toggles
    private final BooleanSetting showBow        = new BooleanSetting("Bow",         true);
    private final BooleanSetting showCrossbow   = new BooleanSetting("Crossbow",    true);
    private final BooleanSetting showTrident    = new BooleanSetting("Trident",     true);
    private final BooleanSetting showSnowball   = new BooleanSetting("Snowball",    true);
    private final BooleanSetting showEgg        = new BooleanSetting("Egg",         true);
    private final BooleanSetting showPearl      = new BooleanSetting("Ender Pearl", true);
    private final BooleanSetting showWindCharge = new BooleanSetting("Wind Charge", true);
    private final BooleanSetting showPotion     = new BooleanSetting("Potion",      true);
    private final BooleanSetting showExpBottle  = new BooleanSetting("Exp Bottle",  false);
    private final BooleanSetting showFishingRod = new BooleanSetting("Fishing Rod", false);

    private final BooleanSetting traceWorldProjectiles = new BooleanSetting("Trace Flying Projectiles", true)
            .setDescription("Predicts and draws the live flight of projectiles already in the air, including other players' pearls and arrows.");

    // Path
    private final ModeSetting pathStyle = new ModeSetting("Path Style", "Neon Tube", "Energy Flow", "Comet", "Dashed", "Orbs")
            .setDescription("Neon Tube: solid glowing tube. Energy Flow: pulses travel toward the landing. Comet: a bright head sweeps the arc. Dashed: marching dashes. Orbs: drifting glow orbs on a faint guide.");
    private final ModeSetting pathOrigin = new ModeSetting("Path Origin", "Main Hand", "Throwing Hand", "Center")
            .setDescription("Where your own held-projectile path starts on screen. Main Hand follows your Minecraft main-hand setting (left or right); Throwing Hand follows whichever hand holds the projectile. The path eases back onto the exact arc, so the landing spot is unchanged.");
    private final SliderSetting pathWidth = new SliderSetting("Path Width", 0.06, 0.02, 0.20, 2)
            .setDescription("Thickness of the path tube in blocks.");
    private final SliderSetting glow = new SliderSetting("Glow", 0.8, 0.0, 1.5, 2)
            .setDescription("Strength of the soft bloom around the path, beam and marker. 0 turns it off.");
    private final SliderSetting flowSpeed = new SliderSetting("Flow Speed", 1.0, 0.0, 4.0, 2)
            .setDescription("Animation speed of pulses, comets, dashes and orbs.");
    private final BooleanSetting throughWalls = new BooleanSetting("Through Walls", true)
            .setDescription("Shows the hidden parts of the path, beam and mesh marker dimmed behind blocks.");

    // Color (the shared Theme / Custom / Rainbow / Gradient system the other world visuals use)
    private final ModeSetting colorMode = new ModeSetting("Color", ThemeColors.THEME, ThemeColors.THEME,
            ThemeColors.CUSTOM, ThemeColors.RAINBOW, ThemeColors.GRADIENT)
            .setDescription("Theme follows the ClickGUI theme and accent; Custom uses Custom Color; Rainbow cycles the hue along the path; Gradient blends the accent at your hand into Gradient End at the landing.");
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(120, 200, 255))
            .setDescription("Path and marker colour when Color is Custom.")
            .setDependsOn("Color:Custom");
    private final ColorSetting gradientEnd = new ColorSetting("Gradient End", new Color(230, 245, 255))
            .setDescription("Colour the path blends into toward the landing when Color is Gradient.")
            .setDependsOn("Color:Gradient");
    private final SliderSetting rainbowSpeed = new SliderSetting("Rainbow Speed", 1.0, 0.1, 4.0, 1)
            .setDescription("How fast the rainbow cycles.")
            .setDependsOn("Color:Rainbow");
    private final ColorSetting hitColor = new ColorSetting("Entity Hit Color", new Color(255, 64, 88))
            .setDescription("Colour the path end, marker and highlight take when the projectile will hit an entity.");

    // Landing
    private final BooleanSetting landingMarker = new BooleanSetting("Landing Marker", true)
            .setDescription("Draws a circle on the ground where the projectile will land.");
    private final ModeSetting markerRenderer = new ModeSetting("Marker Renderer", "Terrain Decal", "Mesh")
            .setDependsOn("Landing Marker")
            .setDescription("Terrain Decal projects the circle onto the real ground with the GPU compositor, so it wraps over slabs, stairs and edges. Mesh draws it as flat geometry; it is also the automatic fallback.");
    private final ModeSetting markerStyle = new ModeSetting("Marker Style", "Halo", "Reticle", "Minimal")
            .setDescription("Look of the landing marker: Halo (soft glowing ring), Reticle (crosshair-style target) or Minimal (thin ring).")
            .setDependsOn("Landing Marker");
    private final SliderSetting markerSize = new SliderSetting("Marker Size", 0.8, 0.3, 2.0, 2)
            .setDescription("Radius of the landing marker, in blocks.")
            .setDependsOn("Landing Marker");
    private final ModeSetting landingBeam = new ModeSetting("Landing Beam", "Pearls", "All", "Off")
            .setDescription("Light shaft above the landing spot: for every ender pearl (yours and other players'), for every projectile, or never.");
    private final SliderSetting beamHeight = new SliderSetting("Beam Height", 6.0, 1.5, 16.0, 1)
            .setDescription("How tall the landing beam is, in blocks.")
            .setDependsOn("!Landing Beam:Off");
    private final BooleanSetting entityHighlight = new BooleanSetting("Entity Highlight", true)
            .setDescription("Tints the hitbox of an entity the projectile will hit.");
    private final BooleanSetting showDistance = new BooleanSetting("Distance Label", true)
            .setDescription("Shows distance to the landing spot above the marker.");
    private final BooleanSetting showFlightTime = new BooleanSetting("Flight Time", true)
            .setDependsOn("Distance Label")
            .setDescription("Adds the predicted time until impact to the label.");

    // Simulation
    private final SliderSetting maxDistance = new SliderSetting("Max Distance", 256, 16, 512, 0)
            .setDescription("Maximum simulation flight distance.");
    private final SliderSetting maxPoints = new SliderSetting("Max Points", 220, 50, 450, 0)
            .setDescription("Maximum calculation physics steps along the flight path.");

    private final List<List<Vec3d>> pointBufferPool = new ArrayList<>();
    // World projectiles only move once per game tick, so their simulated paths (one entity query and one
    // block raycast per step, per projectile) are computed once per tick and reused by every frame in it.
    private final List<TrajectoryResult> worldCache = new ArrayList<>();
    private long worldCacheTick = Long.MIN_VALUE;
    private Object worldCacheWorld;
    private final List<Path> pathPool = new ArrayList<>();
    private final List<ImpactLabel> impactLabels = new ArrayList<>();
    private final List<Marker> pendingDecals = new ArrayList<>();
    private long lastDecalContributionNanos;

    public Trajectory() {
        super("Trajectory", "Shows exactly where your projectile will go before you throw or shoot: draws the predicted flight path and a landing marker for bows, crossbows, tridents, pearls, wind charges, potions and more, and highlights the entity it would hit. Can also trace projectiles already in the air, including other players' pearls. Visual only.", Category.RENDER);
        for (BooleanSetting toggle : List.of(showBow, showCrossbow, showTrident, showSnowball, showEgg, showPearl,
                showWindCharge, showPotion, showExpBottle, showFishingRod, traceWorldProjectiles)) {
            toggle.setSection("Projectiles");
            if (toggle.getDescription() == null || toggle.getDescription().isEmpty()) {
                toggle.setDescription("Shows the predicted path while you hold a " + toggle.getName().toLowerCase(java.util.Locale.ROOT) + ".");
            }
            addSetting(toggle);
        }
        pathStyle.setSection("Path");
        pathOrigin.setSection("Path");
        pathWidth.setSection("Path");
        glow.setSection("Path");
        flowSpeed.setSection("Path");
        throughWalls.setSection("Path");
        colorMode.setSection("Color");
        customColor.setSection("Color");
        gradientEnd.setSection("Color");
        rainbowSpeed.setSection("Color");
        hitColor.setSection("Color");
        landingMarker.setSection("Landing");
        markerRenderer.setSection("Landing");
        markerStyle.setSection("Landing");
        markerSize.setSection("Landing");
        landingBeam.setSection("Landing");
        beamHeight.setSection("Landing");
        entityHighlight.setSection("Landing");
        showDistance.setSection("Landing");
        showFlightTime.setSection("Landing");
        maxDistance.setSection("Simulation");
        maxPoints.setSection("Simulation");
        addSetting(pathStyle);
        addSetting(pathOrigin);
        addSetting(pathWidth);
        addSetting(glow);
        addSetting(flowSpeed);
        addSetting(throughWalls);
        addSetting(colorMode);
        addSetting(customColor);
        addSetting(gradientEnd);
        addSetting(rainbowSpeed);
        addSetting(hitColor);
        addSetting(landingMarker);
        addSetting(markerRenderer);
        addSetting(markerStyle);
        addSetting(markerSize);
        addSetting(landingBeam);
        addSetting(beamHeight);
        addSetting(entityHighlight);
        addSetting(showDistance);
        addSetting(showFlightTime);
        addSetting(maxDistance);
        addSetting(maxPoints);
    }

    @Override
    public void onDisable() {
        clearFrameState();
        clearWorldCache();
    }

    @Override
    protected void onRuntimeStateReleased() {
        clearFrameState();
        clearWorldCache();
    }

    private void clearWorldCache() {
        worldCache.clear();
        worldCacheTick = Long.MIN_VALUE;
        worldCacheWorld = null;
    }

    private List<TrajectoryResult> worldResults(float tickDelta) {
        long tick = mc.world.getTime();
        if (tick != worldCacheTick || mc.world != worldCacheWorld) {
            worldCacheTick = tick;
            worldCacheWorld = mc.world;
            worldCache.clear();
            for (ProjectileData pd : buildWorldProjectilesData(tickDelta)) {
                worldCache.add(simulate(pd, new ArrayList<>()));
            }
        }
        return worldCache;
    }

    private void clearFrameState() {
        impactLabels.clear();
        pendingDecals.clear();
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        try {
            renderTrajectory(matrices, tickDelta);
        } catch (Throwable ignored) {
            clearFrameState();
        }
    }

    private void renderTrajectory(MatrixStack matrices, float tickDelta) {
        clearFrameState();
        if (mc.world == null || mc.player == null) return;

        List<ProjectileData> projectiles = new ArrayList<>();

        // Main hand projectile
        ItemStack mainStack = mc.player.getMainHandStack();
        projectiles.addAll(buildHeldProjectileData(mainStack, tickDelta));
        boolean offHandSource = false;

        // Off hand projectile
        ItemStack offStack = mc.player.getOffHandStack();
        if (projectiles.isEmpty() && !offStack.isEmpty()) {
            projectiles.addAll(buildHeldProjectileData(offStack, tickDelta));
            offHandSource = !projectiles.isEmpty();
        }

        // Held projectiles follow the look every frame; world projectiles come from the per-tick cache.
        List<TrajectoryResult> results = new ArrayList<>(projectiles.size() + 4);
        for (int i = 0; i < projectiles.size(); i++) {
            results.add(simulate(projectiles.get(i), pointBufferFor(i)));
        }
        if (traceWorldProjectiles.isEnabled()) {
            results.addAll(worldResults(tickDelta));
        }

        if (results.isEmpty()) return;

        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cam = camera.getCameraPos();
        Vec3d eye = mc.player.getEyePos();
        double time = (System.nanoTime() % 3_600_000_000_000L) / 1.0e9;
        double flow = time * flowSpeed.getValue();
        boolean firstPerson = mc.options.getPerspective().isFirstPerson();
        Vec3d handOffset = handOffset(offHandSource, tickDelta, firstPerson);
        Palette palette = resolvePalette();
        Color hit = hitColor.getValue();

        // Turn every result into render intent: path samples, impact marker, beam, label.
        List<Path> paths = new ArrayList<>(results.size());
        List<Marker> markers = new ArrayList<>();
        List<Box> hitBoxes = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            TrajectoryResult res = results.get(i);
            if (res.points().size() < 2) continue;
            boolean entityHit = res.hitType() == HitType.ENTITY;
            Path path = pathFor(paths.size());
            buildPath(path, res, res.held() ? handOffset : null, res.held() && firstPerson ? 0.5 : 0.0,
                    palette, entityHit ? hit : null, cam, i, time, flow);
            paths.add(path);

            if (!res.hasHit()) continue;
            Vec3d imp = res.points().get(res.points().size() - 1);
            Color markerPrimary = entityHit ? hit : palette.marker(time);
            Color markerSecondary = entityHit ? brighten(hit, 0.45f) : palette.markerAccent(time);
            Vec3d markerPos = imp;
            Vec3d normal = Vec3d.of(res.hitNormal().getVector());
            double radius = markerSize.getValue();
            if (entityHit && res.hitBox() != null) {
                Box box = res.hitBox();
                markerPos = new Vec3d((box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5);
                normal = new Vec3d(0, 1, 0);
                radius = Math.max(0.45, Math.max(box.getLengthX(), box.getLengthZ()) * 0.85) * markerSize.getValue() / 0.8;
                if (entityHighlight.isEnabled()) hitBoxes.add(box);
            }
            boolean beam = switch (landingBeam.getCurrentMode()) {
                case "All" -> true;
                case "Pearls" -> res.pearl();
                default -> false;
            };
            markers.add(new Marker(markerPos, imp, normal, radius, markerPrimary, markerSecondary, beam, res.held()));
            impactLabels.add(new ImpactLabel(imp, eye.distanceTo(imp), entityHit, res.ticks(), markerPrimary));
        }

        // Your own throw's marker gets a decal slot first, then the nearest in-flight ones.
        markers.sort((a, b) -> a.held() != b.held() ? (a.held() ? -1 : 1)
                : Double.compare(a.impact().squaredDistanceTo(eye), b.impact().squaredDistanceTo(eye)));
        List<Marker> meshMarkers = new ArrayList<>();
        if (landingMarker.isEnabled()) {
            boolean decal = markerRenderer.is("Terrain Decal") && GpuWorldVisualEngine.isHealthy()
                    && System.nanoTime() - lastDecalContributionNanos < DECAL_HEARTBEAT_NANOS;
            for (Marker marker : markers) {
                if (decal && pendingDecals.size() < WorldSceneVisuals.MAX_LANDING_MARKERS) pendingDecals.add(marker);
                else meshMarkers.add(marker);
            }
        }

        boolean xray = throughWalls.isEnabled();
        float glowAmount = (float) glow.getValue();
        float halfWidth = (float) pathWidth.getValue() * 0.5f;
        boolean orbs = pathStyle.is("Orbs");

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(1 << 18)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            matrices.push();
            matrices.translate(-cam.x, -cam.y, -cam.z);
            Matrix4f mat = matrices.peek().getPositionMatrix();

            // Each layer gets its own pass with exactly one live buffer: Immediate.getBuffer() closes the
            // current layer when asked for another, and every layer shares the single allocator.
            // Hidden (x-ray) passes go first and dim; the depth-tested passes then restore full strength
            // wherever the geometry is actually visible.
            for (int pass = xray ? 0 : 2; pass < 4; pass++) {
                boolean hidden = pass < 2;
                boolean additive = pass % 2 == 0;
                RenderLayer layer = additive ? TrajectoryVisualRenderer.glow(hidden) : TrajectoryVisualRenderer.fill(hidden);
                VertexConsumer buf = immediate.getBuffer(layer);
                float strength = hidden ? 0.32f : 1.0f;

                for (Path path : paths) {
                    if (additive) {
                        if (glowAmount > 0f) {
                            TrajectoryVisualRenderer.ribbon(buf, mat, cam, path, halfWidth * 4.5f,
                                    TrajectoryVisualRenderer.HALO, 0.30f * glowAmount * strength);
                        }
                    } else {
                        TrajectoryVisualRenderer.ribbon(buf, mat, cam, path, halfWidth,
                                TrajectoryVisualRenderer.TUBE, (hidden ? 0.35f : 0.92f));
                    }
                }
                if (orbs && additive) {
                    for (int i = 0; i < paths.size(); i++) drawOrbs(buf, mat, cam, paths.get(i), halfWidth, flow, strength);
                }

                for (Marker marker : markers) {
                    if (additive && marker.beam()) drawBeam(buf, mat, cam, marker, glowAmount, strength, time);
                }
                for (Marker marker : meshMarkers) {
                    drawMeshMarker(buf, mat, marker, additive, hidden ? 0.3f : 1.0f, glowAmount, time);
                }
                if (!additive) {
                    for (Box box : hitBoxes) {
                        TrajectoryVisualRenderer.box(buf, mat, box.expand(0.02), hit.getRed() / 255f,
                                hit.getGreen() / 255f, hit.getBlue() / 255f, hidden ? 0.08f : 0.16f);
                    }
                }
                immediate.draw(layer);
            }

            immediate.draw();
            matrices.pop();
        }
    }

    @Override
    public void contributeWorldSceneVisuals(WorldSceneVisuals frame, long nowMillis) {
        lastDecalContributionNanos = System.nanoTime();
        if (!isEnabled() || pendingDecals.isEmpty()) return;
        int style = switch (markerStyle.getCurrentMode()) {
            case "Reticle" -> 1;
            case "Minimal" -> 2;
            default -> 0;
        };
        float intensity = 0.75f + 0.35f * Math.min(1f, (float) glow.getValue());
        for (Marker marker : pendingDecals) {
            frame.addLandingMarker(marker.position(), marker.normal(), (float) marker.radius(), intensity,
                    marker.primary(), marker.secondary(), style);
        }
    }

    /**
     * Camera-relative offset of the hand the path should leave from, or null for the centre of the view.
     * The offset only shifts how the near part of the path is drawn; {@link #buildPath} fades it to zero
     * so the arc rejoins the simulated points well before it lands.
     */
    private Vec3d handOffset(boolean offHandSource, float tickDelta, boolean firstPerson) {
        if (pathOrigin.is("Center") || mc.player == null) return null;
        Arm arm = mc.player.getMainArm();
        if (pathOrigin.is("Throwing Hand") && offHandSource) arm = arm.getOpposite();
        double side = arm == Arm.RIGHT ? 1.0 : -1.0;

        Vec3d look = mc.player.getRotationVec(tickDelta);
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) {
            double yaw = Math.toRadians(mc.player.getYaw(tickDelta));
            right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
        }
        right = right.normalize();
        Vec3d up = right.crossProduct(look).normalize();
        if (firstPerson) {
            return right.multiply(side * 0.34).add(up.multiply(-0.24));
        }
        return right.multiply(side * 0.38).add(0, -0.55, 0);
    }

    /**
     * Resamples the simulated points along a Catmull-Rom curve at even arc-length spacing and assigns each
     * sample its colour, opacity and width for the current style. Every simulated point stays on the curve
     * and the final sample is the exact impact point.
     */
    private void buildPath(Path out, TrajectoryResult res, Vec3d handOffset, double skipStart, Palette palette,
                           Color hit, Vec3d cam, int index, double time, double flow) {
        out.clear();
        List<Vec3d> pts = res.points();
        int n = pts.size();
        double[] cumulative = new double[n];
        for (int i = 1; i < n; i++) cumulative[i] = cumulative[i - 1] + pts.get(i).distanceTo(pts.get(i - 1));
        double total = cumulative[n - 1];
        if (total < 1.0e-4) return;

        String style = pathStyle.getCurrentMode();
        boolean dashed = "Dashed".equals(style);
        double step = Math.max(dashed ? 0.08 : 0.2, total / (dashed ? 1800.0 : 900.0));
        double start = Math.min(skipStart, total * 0.5);
        double converge = Math.min(10.0, total * 0.6);
        double cometHead = (flow * 9.0) % (total + 8.0);
        Vec3d prev = null;

        int seg = 0;
        for (double s = start; ; s += step) {
            boolean last = s >= total;
            if (last) s = total;
            while (seg < n - 2 && cumulative[seg + 1] < s) seg++;
            double segLen = cumulative[seg + 1] - cumulative[seg];
            double u = segLen < 1.0e-9 ? 0 : (s - cumulative[seg]) / segLen;
            Vec3d p = catmullRom(pts.get(Math.max(0, seg - 1)), pts.get(seg), pts.get(seg + 1),
                    pts.get(Math.min(n - 1, seg + 2)), u);
            if (handOffset != null && !last) {
                double k = 1.0 - smoothstep(0.0, converge, s);
                p = p.add(handOffset.multiply(k));
            }

            double f = s / total;
            Color base = palette.along(f, time, index);
            float r = base.getRed() / 255f, g = base.getGreen() / 255f, b = base.getBlue() / 255f;
            if (hit != null) {
                float h = (float) smoothstep(0.55, 1.0, f);
                r += (hit.getRed() / 255f - r) * h;
                g += (hit.getGreen() / 255f - g) * h;
                b += (hit.getBlue() / 255f - b) * h;
            }

            double alpha = 0.95;
            double width = 1.0;
            switch (style) {
                case "Energy Flow" -> {
                    double pulse = Math.pow(0.5 + 0.5 * Math.cos((s / 3.0 - flow * 1.4) * Math.PI * 2.0), 8.0);
                    alpha = 0.55 + 0.45 * pulse;
                    width = 1.0 + 0.7 * pulse;
                    r += (1f - r) * (float) pulse * 0.45f;
                    g += (1f - g) * (float) pulse * 0.45f;
                    b += (1f - b) * (float) pulse * 0.45f;
                }
                case "Comet" -> {
                    double d = cometHead - s;
                    double tail = d >= 0 ? Math.exp(-d / 4.5) : Math.exp(d * 3.0);
                    alpha = 0.30 + 0.70 * tail;
                    width = 0.8 + 0.8 * tail;
                }
                case "Dashed" -> {
                    double ph = s / 0.9 - flow * 1.5;
                    ph -= Math.floor(ph);
                    alpha = 0.95 * smoothstep(0.0, 0.06, ph) * (1.0 - smoothstep(0.5, 0.56, ph));
                }
                case "Orbs" -> {
                    alpha = 0.40;
                    width = 0.55;
                }
                default -> { }
            }

            // Ease in at the start, and taper the width close to the camera so the near part of the path
            // reads as a thin beam leaving the hand instead of a blob filling the screen.
            alpha *= smoothstep(start, start + 0.6, s);
            double camDist = Math.sqrt((p.x - cam.x) * (p.x - cam.x) + (p.y - cam.y) * (p.y - cam.y) + (p.z - cam.z) * (p.z - cam.z));
            width *= MathHelper.clamp(camDist / 4.0, 0.22, 1.0);

            if (prev == null || last || p.squaredDistanceTo(prev) > 1.0e-6) {
                out.add(p.x, p.y, p.z, clamp01(r), clamp01(g), clamp01(b), (float) alpha, (float) width);
                prev = p;
            }
            if (last) break;
        }
    }

    private void drawOrbs(VertexConsumer buf, Matrix4f mat, Vec3d cam, Path path, float halfWidth, double flow, float strength) {
        int n = path.size();
        if (n < 2) return;
        double spacing = 1.6;
        double offset = (flow * 2.2) % spacing;
        double walked = 0;
        double next = offset;
        for (int i = 1; i < n; i++) {
            Vec3d a = sample(path, i - 1), b = sample(path, i);
            double len = a.distanceTo(b);
            while (next <= walked + len && len > 1.0e-9) {
                double u = (next - walked) / len;
                Vec3d p = a.lerp(b, u);
                float w = path.widthAt(i);
                float al = path.alphaAt(i) / 0.40f;
                TrajectoryVisualRenderer.orb(buf, mat, cam, p, halfWidth * 3.2 * w / 0.55,
                        path.redAt(i), path.greenAt(i), path.blueAt(i), 0.85f * al * strength);
                TrajectoryVisualRenderer.orb(buf, mat, cam, p, halfWidth * 1.1 * w / 0.55,
                        1f, 1f, 1f, 0.75f * al * strength);
                next += spacing;
            }
            walked += len;
        }
    }

    private static Vec3d sample(Path path, int i) {
        return path.positionAt(i);
    }

    private void drawBeam(VertexConsumer buf, Matrix4f mat, Vec3d cam, Marker marker, float glowAmount,
                          float strength, double time) {
        Color c = marker.primary();
        float r = c.getRed() / 255f, g = c.getGreen() / 255f, b = c.getBlue() / 255f;
        double height = beamHeight.getValue();
        double width = 0.22 * (0.6 + 0.4 * marker.radius() / 0.8);
        float glowScale = Math.max(0.35f, glowAmount);
        TrajectoryVisualRenderer.beam(buf, mat, cam, marker.impact(), height, width * 2.2, r, g, b,
                0.30f * glowScale * strength, time, 1.8);
        TrajectoryVisualRenderer.beam(buf, mat, cam, marker.impact(), height, width * 0.35,
                r + (1f - r) * 0.6f, g + (1f - g) * 0.6f, b + (1f - b) * 0.6f, 0.85f * strength, time, 1.8);
    }

    /**
     * Geometry version of the landing decal, used when the compositor is unavailable, when Mesh is chosen,
     * or for markers past the decal limit. Mirrors the decal's Halo, Reticle and Minimal layouts.
     */
    private void drawMeshMarker(VertexConsumer buf, Matrix4f mat, Marker marker, boolean additive,
                                float strength, float glowAmount, double time) {
        Vec3d[] basis = TrajectoryVisualRenderer.planeBasis(marker.normal());
        Vec3d center = marker.position().add(marker.normal().normalize().multiply(0.018));
        double radius = marker.radius();
        Color p = marker.primary(), s = marker.secondary();
        float pr = p.getRed() / 255f, pg = p.getGreen() / 255f, pb = p.getBlue() / 255f;
        float sr = s.getRed() / 255f, sg = s.getGreen() / 255f, sb = s.getBlue() / 255f;
        String style = markerStyle.getCurrentMode();
        double tau = Math.PI * 2.0;
        int segs = 64;

        if (!additive) {
            float fill = "Minimal".equals(style) ? 0.10f : ("Reticle".equals(style) ? 0.08f : 0.20f);
            TrajectoryVisualRenderer.band(buf, mat, center, basis, 0, radius, 0, tau, segs,
                    sr, sg, sb, fill * strength, fill * 0.25f * strength);
            TrajectoryVisualRenderer.softRing(buf, mat, center, basis, radius, radius * 0.035, 0, tau, segs,
                    pr, pg, pb, 0.95f * strength);
            TrajectoryVisualRenderer.band(buf, mat, center, basis, 0, radius * 0.07, 0, tau, 16,
                    pr, pg, pb, 0.95f * strength, 0.6f * strength);
            return;
        }

        float halo = 0.45f * Math.max(0.3f, glowAmount) * strength;
        TrajectoryVisualRenderer.band(buf, mat, center, basis, radius, radius * 1.32, 0, tau, segs,
                pr, pg, pb, halo, 0f);
        TrajectoryVisualRenderer.band(buf, mat, center, basis, radius * 0.72, radius, 0, tau, segs,
                pr, pg, pb, 0f, halo * 0.8f);

        if ("Minimal".equals(style)) {
            drawPulse(buf, mat, center, basis, radius, sr, sg, sb, strength, time);
            return;
        }

        double spin = time * 0.22 * tau;
        int arcs = 3;
        double arcSweep = tau / arcs * 0.52;
        for (int i = 0; i < arcs; i++) {
            TrajectoryVisualRenderer.softRing(buf, mat, center, basis, radius * 1.24, radius * 0.024,
                    -spin + i * tau / arcs, arcSweep, 20, sr, sg, sb, 0.85f * strength);
        }
        int dashes = 18;
        double dashSweep = tau / dashes * 0.5;
        double dashSpin = time * 0.30 * tau / dashes;
        for (int i = 0; i < dashes; i++) {
            TrajectoryVisualRenderer.softRing(buf, mat, center, basis, radius * 0.64, radius * 0.018,
                    dashSpin + i * tau / dashes, dashSweep, 3, sr, sg, sb, 0.75f * strength);
        }
        if ("Reticle".equals(style)) {
            for (int i = 0; i < 4; i++) {
                double a = i * tau / 4.0;
                Vec3d dir = basis[0].multiply(Math.cos(a)).add(basis[1].multiply(Math.sin(a)));
                Vec3d mid = center.add(dir.multiply(radius * 1.24));
                Vec3d side = basis[0].multiply(-Math.sin(a)).add(basis[1].multiply(Math.cos(a)));
                TrajectoryVisualRenderer.band(buf, mat, mid, new Vec3d[] { dir, side }, 0, radius * 0.06, 0, tau, 8,
                        pr, pg, pb, 0.9f * strength, 0.2f * strength);
            }
        } else {
            drawPulse(buf, mat, center, basis, radius, pr, pg, pb, strength, time);
        }
    }

    private static void drawPulse(VertexConsumer buf, Matrix4f mat, Vec3d center, Vec3d[] basis, double radius,
                                  float r, float g, float b, float strength, double time) {
        double phase = (time * 0.55) % 1.0;
        double pulseRadius = phase * 1.35 * radius;
        if (pulseRadius < radius * 0.05) return;
        TrajectoryVisualRenderer.softRing(buf, mat, center, basis, pulseRadius, radius * 0.02, 0, Math.PI * 2, 64,
                r, g, b, (float) (0.8 * (1.0 - phase)) * strength);
    }

    private Path pathFor(int index) {
        while (pathPool.size() <= index) pathPool.add(new Path());
        return pathPool.get(index);
    }

    private static Vec3d catmullRom(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return new Vec3d(
                0.5 * (2 * p1.x + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3),
                0.5 * (2 * p1.y + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3),
                0.5 * (2 * p1.z + (-p0.z + p2.z) * t + (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * t2 + (-p0.z + 3 * p1.z - 3 * p2.z + p3.z) * t3));
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        if (edge1 <= edge0) return x >= edge1 ? 1.0 : 0.0;
        double t = MathHelper.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static Color brighten(Color c, float amount) {
        return new Color(
                Math.round(c.getRed() + (255 - c.getRed()) * amount),
                Math.round(c.getGreen() + (255 - c.getGreen()) * amount),
                Math.round(c.getBlue() + (255 - c.getBlue()) * amount));
    }

    private static Color lerp(Color a, Color b, double t) {
        float k = (float) MathHelper.clamp(t, 0.0, 1.0);
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * k),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * k),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * k));
    }

    /** Resolves the shared Color mode into path, glow and marker colours. */
    private Palette resolvePalette() {
        String mode = colorMode.getCurrentMode();
        double hueSpeed = 0.15 * rainbowSpeed.getValue();
        if (ThemeColors.CUSTOM.equals(mode)) {
            Color custom = customColor.getValue();
            return new Palette(custom, brighten(custom, 0.6f), false, false, hueSpeed);
        }
        if (ThemeColors.RAINBOW.equals(mode)) {
            return new Palette(Color.WHITE, Color.WHITE, true, false, hueSpeed);
        }
        if (ThemeColors.GRADIENT.equals(mode)) {
            return new Palette(new Color(ThemeColors.accent() & 0xFFFFFF), gradientEnd.getValue(), false, false, hueSpeed);
        }
        // Theme: follows the ClickGUI (its custom highlight pair when on, else the menu theme's colours).
        if (ClickGUIModule.customHighlight != null && ClickGUIModule.customHighlight.isEnabled()
                && ClickGUIModule.highlightColor1 != null && ClickGUIModule.highlightColor2 != null) {
            return new Palette(ClickGUIModule.highlightColor1.getValue(), ClickGUIModule.highlightColor2.getValue(), false, false, hueSpeed);
        }
        String menuName = ClickGUIModule.theme != null ? ClickGUIModule.theme.getCurrentMode() : UiThemeMaterial.APEXX.name();
        UiThemeMaterial.Theme menu = UiThemeMaterial.theme(menuName);
        Color accent = new Color(menu.accent() & 0xFFFFFF);
        Color light = new Color(menu.logoLight() & 0xFFFFFF);
        // The Apexx theme is monochrome silver; give it the client's animated chrome shimmer.
        return new Palette(accent, light, false, menu == UiThemeMaterial.APEXX, hueSpeed);
    }

    private record Palette(Color primary, Color secondary, boolean chroma, boolean silver, double hueSpeed) {
        Color along(double f, double time, int index) {
            if (chroma) {
                float hue = (float) ((time * hueSpeed + f * 0.6 + index * 0.2) % 1.0);
                return Color.getHSBColor(hue, 0.75f, 1.0f);
            }
            if (silver) {
                return lerp(AccentState.apexxSilverColor((long) (f * -1400.0)), secondary, f * 0.6);
            }
            return lerp(primary, secondary, f * 0.85);
        }

        Color marker(double time) {
            if (chroma) return Color.getHSBColor((float) ((time * hueSpeed + 0.6) % 1.0), 0.75f, 1.0f);
            if (silver) return AccentState.apexxSilverColor();
            return primary;
        }

        Color markerAccent(double time) {
            if (chroma) return Color.getHSBColor((float) ((time * hueSpeed + 0.1) % 1.0), 0.6f, 1.0f);
            return secondary;
        }
    }

    private List<ProjectileData> buildHeldProjectileData(ItemStack stack, float tickDelta) {
        List<ProjectileData> list = new ArrayList<>();
        if (stack.isEmpty() || mc.player == null) return list;

        Item item = stack.getItem();
        Vec3d startPos = mc.player.getCameraPosVec(tickDelta).add(0, -0.1, 0);
        Vec3d look = mc.player.getRotationVec(tickDelta);
        double g = 0.05, d = 0.99, wd = 0.6;

        if (item instanceof BowItem && showBow.isEnabled()) {
            boolean drawing = mc.player.isUsingItem() && mc.player.getActiveItem() == stack;
            float pull = drawing ? BowItem.getPullProgress(72000 - mc.player.getItemUseTimeLeft()) : 1.0f;
            if (pull >= 0.05f) {
                list.add(new ProjectileData(g, d, look.multiply(3.0 * pull), startPos, false, wd, ORDER_MDG, true, false));
            }
        } else if (item instanceof CrossbowItem && showCrossbow.isEnabled()) {
            if (CrossbowItem.isCharged(stack)) {
                double speed = 3.15;
                ChargedProjectilesComponent charged = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
                if (charged != null) {
                    for (ItemStack proj : charged.getProjectiles()) {
                        if (proj.isOf(Items.FIREWORK_ROCKET)) { speed = 1.6; g = 0; break; }
                    }
                }
                Vec3d vel = look.multiply(speed);
                list.add(new ProjectileData(g, d, vel, startPos, false, wd, ORDER_MDG, true, false));
                if (hasEnchantment(stack, Enchantments.MULTISHOT)) {
                    list.add(new ProjectileData(g, d, rotateY(vel,  Math.toRadians(10)), startPos, false, wd, ORDER_MDG, true, false));
                    list.add(new ProjectileData(g, d, rotateY(vel, -Math.toRadians(10)), startPos, false, wd, ORDER_MDG, true, false));
                }
            }
        } else if (item instanceof TridentItem && showTrident.isEnabled()) {
            if (!hasEnchantment(stack, Enchantments.RIPTIDE)) {
                list.add(new ProjectileData(g, d, look.multiply(2.5), startPos, false, 0.99, ORDER_MDG, true, false));
            }
        } else if ((stack.isOf(Items.SNOWBALL) && showSnowball.isEnabled())
                || (stack.isOf(Items.EGG) && showEgg.isEnabled())
                || (stack.isOf(Items.ENDER_PEARL) && showPearl.isEnabled())) {
            list.add(new ProjectileData(0.03, d, look.multiply(1.5), startPos, false, 0.8, ORDER_GDM, true,
                    stack.isOf(Items.ENDER_PEARL)));
        } else if (stack.isOf(Items.WIND_CHARGE) && showWindCharge.isEnabled()) {
            list.add(new ProjectileData(0, 0.95, look, startPos, false, 0.8, ORDER_MDG, true, false));
        } else if ((stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION)) && showPotion.isEnabled()) {
            Vec3d dir = angleFromRot(mc.player.getPitch(tickDelta), mc.player.getYaw(tickDelta), -20f);
            list.add(new ProjectileData(g, d, dir.multiply(0.5), startPos, false, 0.8, ORDER_GDM, true, false));
        } else if (stack.isOf(Items.EXPERIENCE_BOTTLE) && showExpBottle.isEnabled()) {
            Vec3d dir = angleFromRot(mc.player.getPitch(tickDelta), mc.player.getYaw(tickDelta), -20f).normalize();
            list.add(new ProjectileData(0.07, d, dir.multiply(0.7), startPos, false, 0.8, ORDER_GDM, true, false));
        }

        return list;
    }

    private List<ProjectileData> buildWorldProjectilesData(float tickDelta) {
        List<ProjectileData> list = new ArrayList<>();
        if (mc.world == null) return list;

        for (Entity entity : mc.world.getEntities()) {
            if (entity == null || !entity.isAlive()) continue;

            Vec3d pos = entity.getLerpedPos(tickDelta);
            Vec3d vel = entity.getVelocity();
            if (vel.lengthSquared() < 0.001) continue;

            if (entity instanceof EnderPearlEntity && showPearl.isEnabled()) {
                list.add(new ProjectileData(0.03, 0.99, vel, pos, false, 0.8, ORDER_GDM, false, true));
            } else if (entity instanceof ArrowEntity || entity instanceof SpectralArrowEntity) {
                if (showBow.isEnabled()) {
                    list.add(new ProjectileData(0.05, 0.99, vel, pos, false, 0.6, ORDER_MDG, false, false));
                }
            } else if (entity instanceof WindChargeEntity && showWindCharge.isEnabled()) {
                list.add(new ProjectileData(0, 0.95, vel, pos, false, 0.8, ORDER_MDG, false, false));
            } else if (entity instanceof TridentEntity && showTrident.isEnabled()) {
                list.add(new ProjectileData(0.05, 0.99, vel, pos, false, 0.99, ORDER_MDG, false, false));
            } else if (entity instanceof PotionEntity && showPotion.isEnabled()) {
                list.add(new ProjectileData(0.05, 0.99, vel, pos, false, 0.8, ORDER_GDM, false, false));
            }
        }
        return list;
    }

    private List<Vec3d> pointBufferFor(int index) {
        while (pointBufferPool.size() <= index) {
            pointBufferPool.add(new ArrayList<>());
        }
        List<Vec3d> buf = pointBufferPool.get(index);
        buf.clear();
        return buf;
    }

    private TrajectoryResult simulate(ProjectileData pd, List<Vec3d> points) {
        Vec3d pos = pd.startPos;
        Vec3d prevPos = pos;
        Vec3d vel = pd.initialVelocity;
        double drag = pd.drag;
        double gravity = pd.gravity;
        HitType hitType = HitType.NONE;
        Box hitBox = null;
        Direction hitNormal = Direction.UP;

        final int maxSteps = (int) maxPoints.getValue();
        final double maxDistSq = maxDistance.getValue() * maxDistance.getValue();

        int ticks = 0;
        for (int i = 0; i < maxSteps; i++) {
            ticks = i + 1;
            points.add(pos);
            if (pos.squaredDistanceTo(pd.startPos) > maxDistSq) break;

            for (int step : pd.order) {
                if      (step == 0) pos = pos.add(vel);
                else if (step == 1) vel = vel.multiply(drag);
                else                vel = new Vec3d(vel.x, vel.y - gravity, vel.z);
            }

            Box searchBox = new Box(prevPos, pos).expand(0.8);
            Entity closestEntity = null;
            double closestDistSq = Double.MAX_VALUE;
            Vec3d entityHitPos = null;

            List<Entity> nearby = mc.world.getOtherEntities(mc.player, searchBox, e ->
                    !e.isSpectator() && e.isAlive()
                            && !(e instanceof ProjectileEntity)
                            && !(e instanceof ItemEntity)
                            && !(e instanceof ExperienceOrbEntity)
                            && !(e instanceof EnderDragonEntity));

            for (Entity entity : nearby) {
                Optional<Vec3d> hit = entity.getBoundingBox().raycast(prevPos, pos);
                if (hit.isPresent()) {
                    double dsq = prevPos.squaredDistanceTo(hit.get());
                    if (dsq < closestDistSq) {
                        closestDistSq = dsq;
                        closestEntity = entity;
                        entityHitPos = hit.get();
                    }
                }
            }

            HitResult blockHit = mc.world.raycast(new RaycastContext(prevPos, pos,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));

            if (blockHit.getType() != HitResult.Type.MISS
                    && prevPos.squaredDistanceTo(blockHit.getPos()) < closestDistSq) {
                pos = blockHit.getPos();
                points.add(pos);
                if (blockHit instanceof BlockHitResult bhr) {
                    hitBox = new Box(bhr.getBlockPos());
                    hitNormal = bhr.getSide();
                }
                hitType = HitType.BLOCK;
                break;
            }

            if (entityHitPos != null && closestEntity != null) {
                pos = entityHitPos;
                points.add(pos);
                hitBox = closestEntity.getBoundingBox();
                hitType = HitType.ENTITY;
                break;
            }

            if (pos.y < mc.world.getBottomY() - 100) break;
            prevPos = pos;
        }

        return new TrajectoryResult(points, hitType, hitBox, hitNormal, pd.held, ticks, pd.pearl);
    }


    private static Vec3d angleFromRot(float pitch, float yaw, float pitchOffset) {
        float k = -MathHelper.sin(yaw * 0.017453292f) * MathHelper.cos(pitch * 0.017453292f);
        float l = -MathHelper.sin((pitch + pitchOffset) * 0.017453292f);
        float m =  MathHelper.cos(yaw * 0.017453292f) * MathHelper.cos(pitch * 0.017453292f);
        return new Vec3d(k, l, m).normalize();
    }

    private static Vec3d rotateY(Vec3d v, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return new Vec3d(v.x * cos + v.z * sin, v.y, -v.x * sin + v.z * cos);
    }

    private boolean hasEnchantment(ItemStack stack, RegistryKey<Enchantment> key) {
        try {
            var registry = mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
            return EnchantmentHelper.getLevel(registry.getOrThrow(key), stack) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onRender(DrawContext context, float tickDeltaArg) {
        if (!showDistance.isEnabled() || impactLabels.isEmpty()) return;
        if (mc.world == null || mc.player == null) return;

        float tickDelta = mc.getRenderTickCounter().getTickProgress(true);

        if (Msdf.ready()) {
            int screenW = Msdf.width();
            int screenH = Msdf.height();
            if (screenW > 0 && screenH > 0 && Msdf.begin()) {
                try {
                    renderMsdfLabels(tickDelta, screenW, screenH);
                    return;
                } finally {
                    Msdf.end();
                }
            }
        }

        renderFallbackLabels(context, tickDelta);
    }

    private void renderMsdfLabels(float tickDelta, int screenW, int screenH) {
        MsdfFont semiBold = Msdf.semiBold();
        MsdfFont regular = Msdf.regular();
        if (semiBold == null) return;
        Matrix4f viewProj = WorldProjector.viewProjection(tickDelta);

        for (ImpactLabel label : impactLabels) {
            Vec3d target = label.pos().add(0, 0.45, 0);
            Box pt = new Box(target.x, target.y, target.z, target.x, target.y, target.z);
            WorldProjector.Projected p = WorldProjector.project(viewProj, pt, screenW, screenH);
            if (!p.visible()) continue;

            float cx = p.centerX();
            float cy = p.minY();

            float dist = (float) label.distance();
            float distFactor = (float) MathHelper.clamp(16.0 / Math.max(dist, 1.0), 0.72f, 1.20f);
            float fontSize = 15.0f * distFactor;

            String distanceText = String.format("%.1fm", dist);
            String timeText = showFlightTime.isEnabled() ? String.format("%.2fs", label.ticks() / 20.0) : "";
            boolean entityHit = label.entity();
            String hitText = "HIT";

            MsdfFont timeFont = regular != null ? regular : semiBold;

            float textH = semiBold.getTextHeight(fontSize);
            float padX = fontSize * 0.50f;
            float padY = fontSize * 0.32f;
            float radius = fontSize * 0.36f;

            float dotDiameter = fontSize * 0.36f;
            float dotGap = fontSize * 0.32f;

            float hitW = entityHit ? semiBold.getWidth(hitText, fontSize * 0.82f) + fontSize * 0.45f : 0.0f;
            float hitGap = entityHit ? fontSize * 0.28f : 0.0f;

            float distW = semiBold.getWidth(distanceText, fontSize);
            String sep = " · ";
            float sepW = !timeText.isEmpty() ? timeFont.getWidth(sep, fontSize) : 0.0f;
            float timeW = !timeText.isEmpty() ? timeFont.getWidth(timeText, fontSize * 0.90f) : 0.0f;

            float contentW = dotDiameter + dotGap + hitW + hitGap + distW + sepW + timeW;
            float boxW = contentW + padX * 2.0f;
            float boxH = textH + padY * 2.0f;

            float boxX = cx - boxW * 0.5f;
            float boxY = cy - boxH - 6.0f;

            int primaryRgb = label.color().getRGB() & 0xFFFFFF;
            int accentColor = 0xFF000000 | primaryRgb;
            int dotColor = entityHit ? 0xFFFF3B56 : accentColor;

            // Outer subtle antialiased outline / accent glow
            Msdf.rect(boxX - 1.0f, boxY - 1.0f, boxW + 2.0f, boxH + 2.0f, radius + 1.0f,
                    entityHit ? 0x80FF3B56 : (0x40000000 | primaryRgb));
            // Card background (deep modern dark glass)
            Msdf.rect(boxX, boxY, boxW, boxH, radius, 0xDE0B0E14);

            float curX = boxX + padX;
            float dotY = boxY + (boxH - dotDiameter) * 0.5f;
            Msdf.rect(curX, dotY, dotDiameter, dotDiameter, dotDiameter * 0.5f, dotColor);
            curX += dotDiameter + dotGap;

            if (entityHit) {
                float badgeH = fontSize * 0.90f;
                float badgeY = boxY + (boxH - badgeH) * 0.5f;
                float badgeTextW = semiBold.getWidth(hitText, fontSize * 0.80f);
                float badgeW = badgeTextW + fontSize * 0.40f;
                Msdf.rect(curX, badgeY, badgeW, badgeH, radius * 0.6f, 0x50FF3B56);
                float badgeTextX = curX + (badgeW - badgeTextW) * 0.5f;
                float badgeTextY = boxY + (boxH - semiBold.getTextHeight(fontSize * 0.80f)) * 0.5f;
                Msdf.textWithShadow(semiBold, hitText, badgeTextX, badgeTextY, fontSize * 0.80f, 0xFFFF4A60);
                curX += badgeW + hitGap;
            }

            float textY = boxY + (boxH - textH) * 0.5f;
            Msdf.textWithShadow(semiBold, distanceText, curX, textY, fontSize, 0xFFFFFFFF);
            curX += distW;

            if (!timeText.isEmpty()) {
                Msdf.textWithShadow(timeFont, sep, curX, textY, fontSize, 0x8094A3B8);
                curX += sepW;
                float timeFontSize = fontSize * 0.90f;
                float timeTextY = boxY + (boxH - timeFont.getTextHeight(timeFontSize)) * 0.5f;
                Msdf.textWithShadow(timeFont, timeText, curX, timeTextY, timeFontSize, 0xFFCBD5E1);
            }
        }
    }

    private void renderFallbackLabels(DrawContext context, float tickDelta) {
        if (mc.textRenderer == null || mc.gameRenderer == null) return;
        Camera camera = mc.gameRenderer.getCamera();
        float fov = ((GameRendererAccessor) mc.gameRenderer).callGetFov(camera, tickDelta, true);

        for (ImpactLabel label : impactLabels) {
            Vec3d screen = worldToScreen(label.pos().add(0, 0.45, 0), camera, fov);
            if (screen == null) continue;
            String distance = String.format("%.1fm", label.distance());
            boolean hit = label.entity();
            String time = showFlightTime.isEnabled() ? String.format("%.2fs", label.ticks() / 20.0) : "";

            int hitW = hit ? mc.textRenderer.getWidth("HIT ") : 0;
            int distW = mc.textRenderer.getWidth(distance);
            String sep = " · ";
            int sepW = !time.isEmpty() ? mc.textRenderer.getWidth(sep) : 0;
            int timeW = !time.isEmpty() ? mc.textRenderer.getWidth(time) : 0;

            int dotSize = 4;
            int dotGap = 4;
            int padX = 6;
            int padY = 4;
            int contentW = dotSize + dotGap + hitW + distW + sepW + timeW;
            int boxW = contentW + padX * 2;
            int boxH = 9 + padY * 2;

            int x = (int) Math.round(screen.x - boxW / 2.0);
            int y = (int) Math.round(screen.y - boxH - 4);

            int accent = 0xFF000000 | (label.color().getRGB() & 0xFFFFFF);
            int dotColor = hit ? 0xFFFF3B56 : accent;

            // Background card & top accent line
            context.fill(x, y, x + boxW, y + boxH, 0xD00B0E14);
            context.fill(x, y, x + boxW, y + 1, dotColor);

            // Left indicator dot
            context.fill(x + padX, y + (boxH - dotSize) / 2, x + padX + dotSize, y + (boxH + dotSize) / 2, dotColor);

            int textX = x + padX + dotSize + dotGap;
            int textY = y + padY;

            if (hit) {
                context.drawTextWithShadow(mc.textRenderer, "HIT ", textX, textY, 0xFFFF4A60);
                textX += hitW;
            }
            context.drawTextWithShadow(mc.textRenderer, distance, textX, textY, 0xFFFFFFFF);
            textX += distW;

            if (!time.isEmpty()) {
                context.drawTextWithShadow(mc.textRenderer, sep, textX, textY, 0x8094A3B8);
                textX += sepW;
                context.drawTextWithShadow(mc.textRenderer, time, textX, textY, 0xFFCBD5E1);
            }
        }
    }

    private Vec3d worldToScreen(Vec3d worldPos, Camera camera, float activeFov) {
        Vec3d camPos = camera.getCameraPos();
        Vector3f rel = worldPos.toVector3f().sub((float) camPos.x, (float) camPos.y, (float) camPos.z);

        Matrix4f proj = new Matrix4f().perspective(
                (float) Math.toRadians(activeFov),
                (float) mc.getWindow().getFramebufferWidth() / mc.getWindow().getFramebufferHeight(),
                0.05f,
                mc.gameRenderer.getViewDistanceBlocks());

        Matrix4f view = new Matrix4f()
                .rotate((float) Math.toRadians(camera.getPitch()), 1, 0, 0)
                .rotate((float) Math.toRadians(camera.getYaw() + 180), 0, 1, 0);

        Vector4f clip = new Vector4f(rel.x, rel.y, rel.z, 1.0f).mul(view).mul(proj);
        if (clip.w <= 0.0f) return null;

        Vector3f ndc = new Vector3f(clip.x / clip.w, clip.y / clip.w, clip.z / clip.w);
        if (Math.abs(ndc.x) > 1.0f || Math.abs(ndc.y) > 1.0f) return null;

        double screenX = (ndc.x + 1.0) / 2.0 * mc.getWindow().getScaledWidth();
        double screenY = (1.0 - ndc.y) / 2.0 * mc.getWindow().getScaledHeight();
        return new Vec3d(screenX, screenY, ndc.z);
    }

    private record ProjectileData(
            double gravity, double drag, Vec3d initialVelocity,
            Vec3d startPos, boolean hasWaterCollision, double waterDrag,
            List<Integer> order, boolean held, boolean pearl) {}

    private enum HitType {
        NONE,
        BLOCK,
        ENTITY
    }

    private record TrajectoryResult(List<Vec3d> points, HitType hitType, Box hitBox, Direction hitNormal,
                                    boolean held, int ticks, boolean pearl) {
        private boolean hasHit() {
            return hitType != HitType.NONE;
        }
    }

    /** Render intent for one impact: decal/mesh centre, exact impact point, plane normal and colours. */
    private record Marker(Vec3d position, Vec3d impact, Vec3d normal, double radius,
                          Color primary, Color secondary, boolean beam, boolean held) {}

    private record ImpactLabel(Vec3d pos, double distance, boolean entity, int ticks, Color color) {}
}
