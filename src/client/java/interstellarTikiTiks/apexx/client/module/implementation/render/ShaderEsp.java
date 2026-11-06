package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.enemy.EnemyManager;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.EntityChamsProvider;
import interstellarTikiTiks.apexx.client.module.hooks.ShaderEspProvider;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.render.msdf.Msdf;
import interstellarTikiTiks.apexx.client.render.msdf.MsdfFont;
import interstellarTikiTiks.apexx.client.render.msdf.WorldProjector;
import interstellarTikiTiks.apexx.client.render.visuals.ShaderEspParams;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.render.RenderUtils;
import interstellarTikiTiks.apexx.client.util.render.types.ShaderRenderLayers;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The one entity ESP (the former Chams, 2D ESP and MobESP are merged in). Every highlight style is its own
 * toggle and they stack freely:
 *
 * <ul>
 *   <li><b>Shader</b>: a GPU pass that tints, outlines and glows the entity's whole rendered silhouette
 *       (armor and held items included), reusing vanilla's entity-outline framebuffer as its mask.</li>
 *   <li><b>Chams</b>: the live model re-drawn in a flat colour through walls, with a separate tint for the
 *       parts in direct view and an optional additive halo ({@code LivingEntityRendererMixin}).</li>
 *   <li><b>2D Boxes</b>: screen-space corners or full boxes with health, absorption and armor bars,
 *       health text and distance.</li>
 *   <li><b>3D Boxes</b>: 8-corner wave-gradient boxes in the world, plus tracers.</li>
 * </ul>
 *
 * Targets are players (friends, enemies, bots filtered), mobs by kind (hostile, passive, villagers, water,
 * ambient; each with its own colour) and end crystals. Dropped items belong to ItemESP.
 */
public final class ShaderEsp extends Module implements ShaderEspProvider, EntityChamsProvider {
    public static ShaderEsp INSTANCE;

    private static final String TARGETS = "Targets";
    private static final String MOBS = "Mobs";
    private static final String COLOR = "Color";
    private static final String SHADER = "Shader";
    private static final String CHAMS = "Chams";
    private static final String BOX2D = "2D Boxes";
    private static final String BOX3D = "3D Boxes";
    private static final float CORNER_SEGMENT_FRACTION = 0.27f;

    private enum MobKind { HOSTILE, PASSIVE, VILLAGER, WATER, AMBIENT, NONE }

    // ---- Targets ----
    private final SliderSetting range = new SliderSetting("Range", 64, 8, 256, 0)
            .setDescription("Farthest distance in blocks at which entities are highlighted.").setSection(TARGETS);
    private final BooleanSetting targetPlayers = new BooleanSetting("Players", true)
            .setDescription("Highlights other players.").setSection(TARGETS);
    private final BooleanSetting includeFriends = new BooleanSetting("Friends", true)
            .setDescription("Also highlights players on your friends list, in Friend Color.").setSection(TARGETS);
    private final BooleanSetting targetSelf = new BooleanSetting("Self", false)
            .setDescription("Highlights your own player in third person.").setSection(TARGETS);
    private final BooleanSetting invisibles = new BooleanSetting("Invisible", true)
            .setDescription("Includes invisible players and mobs.").setSection(TARGETS);
    private final BooleanSetting targetCrystals = new BooleanSetting("End Crystals", true)
            .setDescription("Highlights end crystals.").setSection(TARGETS);
    private final BooleanSetting throughWalls = new BooleanSetting("Through Walls", true)
            .setDescription("Keeps highlights visible through blocks; off only shows entities you can see.").setSection(TARGETS);

    // ---- Mobs (the former MobESP kinds) ----
    private final BooleanSetting hostile = new BooleanSetting("Hostile", false)
            .setDescription("Zombies, skeletons, creepers and the other monsters.").setSection(MOBS);
    private final BooleanSetting passive = new BooleanSetting("Passive", false)
            .setDescription("Farm animals and other peaceful creatures.").setSection(MOBS);
    private final BooleanSetting villagers = new BooleanSetting("Villagers & Traders", false)
            .setDescription("Villagers and wandering traders.").setSection(MOBS);
    private final BooleanSetting water = new BooleanSetting("Water Mobs", false)
            .setDescription("Fish, squid, dolphins and the like.").setSection(MOBS);
    private final BooleanSetting ambient = new BooleanSetting("Ambient", false)
            .setDescription("Bats.").setSection(MOBS);
    private final BooleanSetting mobColorByKind = new BooleanSetting("Color Mobs By Kind", true)
            .setDescription("Draws each mob kind in its own colour below; off uses the Mode colour for mobs too.").setSection(MOBS);
    private final ColorSetting hostileColor = new ColorSetting("Hostile Color", new Color(255, 70, 70))
            .setDependsOn("Hostile,Color Mobs By Kind").setSection(MOBS);
    private final ColorSetting passiveColor = new ColorSetting("Passive Color", new Color(90, 230, 110))
            .setDependsOn("Passive,Color Mobs By Kind").setSection(MOBS);
    private final ColorSetting villagerColor = new ColorSetting("Villager Color", new Color(255, 170, 60))
            .setDependsOn("Villagers & Traders,Color Mobs By Kind").setSection(MOBS);
    private final ColorSetting waterColor = new ColorSetting("Water Color", new Color(70, 150, 255))
            .setDependsOn("Water Mobs,Color Mobs By Kind").setSection(MOBS);
    private final ColorSetting ambientColor = new ColorSetting("Ambient Color", new Color(190, 190, 200))
            .setDependsOn("Ambient,Color Mobs By Kind").setSection(MOBS);

    // ---- Color ----
    private final ModeSetting mode = new ModeSetting("Mode", "Aurora",
            "Aurora", "Spectrum", "Pearl", "Dual Gradient", "Theme Sync", "Custom")
            .setDescription("Colour animation used by every style. Theme Sync follows the ClickGUI accent.").setSection(COLOR);
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(0, 220, 255))
            .setDescription("Main highlight colour for colour modes that use custom colours.").setSection(COLOR);
    private final ColorSetting secondaryColor = new ColorSetting("Secondary Color", new Color(155, 95, 255))
            .setDescription("Second colour of two-colour gradients.").setSection(COLOR);
    private final ColorSetting friendColor = new ColorSetting("Friend Color", new Color(75, 255, 145))
            .setDescription("Colour used for players on your friends list.").setSection(COLOR);
    private final ColorSetting enemyColor = new ColorSetting("Enemy Color", new Color(255, 72, 92))
            .setDescription("Colour used for players on your enemies list.").setSection(COLOR);

    // ---- Shader ----
    private final BooleanSetting shader = new BooleanSetting("Shader", true)
            .setDescription("The shader pass: tints, outlines and glows the entity model itself.").setSection(SHADER);
    private final BooleanSetting shaderFill = new BooleanSetting("Shader Fill", true)
            .setDescription("Fills the whole entity model, armor and held items included, with a colour tint.").setSection(SHADER);
    private final ModeSetting fillStyle = new ModeSetting("Fill Style", "Gradient", "Solid", "Gradient", "Smoke")
            .setDescription("Look of the fill: Solid colour, an animated two-colour Gradient, or flowing Smoke.").setSection(SHADER);
    private final SliderSetting fillAlpha = new SliderSetting("Fill Alpha", 0.38, 0.0, 1.0, 2)
            .setDescription("How opaque the model fill is.").setSection(SHADER);
    private final SliderSetting fillBrightness = new SliderSetting("Fill Brightness", 1.0, 0.2, 1.8, 2)
            .setDescription("Brightness of the model fill colour.").setSection(SHADER);
    private final SliderSetting gradientAngle = new SliderSetting("Gradient Angle", 50, -180, 180, 0)
            .setDescription("Direction in degrees in which the Gradient fill runs.").setSection(SHADER);
    private final SliderSetting smokeScale = new SliderSetting("Smoke Scale", 2.6, 0.35, 8.0, 2)
            .setDescription("Size of the swirls in the Smoke fill.").setSection(SHADER);
    private final SliderSetting smokeSpeed = new SliderSetting("Smoke Speed", 0.55, 0.0, 3.0, 2)
            .setDescription("How fast the Smoke fill flows.").setSection(SHADER);
    private final SliderSetting smokeOctaves = new SliderSetting("Smoke Octaves", 4, 1, 6, 0)
            .setDescription("Detail layers in the Smoke fill; more is finer but costs more frames.").setSection(SHADER);
    private final SliderSetting smokeContrast = new SliderSetting("Smoke Contrast", 1.2, 0.35, 3.0, 2)
            .setDescription("Contrast between light and dark areas of the Smoke fill.").setSection(SHADER);
    private final BooleanSetting shaderOutline = new BooleanSetting("Shader Outline", true)
            .setDescription("Draws a crisp line around each entity's outline.").setSection(SHADER);
    private final SliderSetting outlineWidth = new SliderSetting("Outline Width", 2.0, 0.5, 12.0, 1)
            .setDescription("Thickness of the outline in pixels.").setSection(SHADER);
    private final SliderSetting outlineAlpha = new SliderSetting("Outline Alpha", 0.95, 0.0, 1.0, 2)
            .setDescription("How opaque the outline is.").setSection(SHADER);
    private final SliderSetting outlineIntensity = new SliderSetting("Outline Intensity", 1.15, 0.1, 3.0, 2)
            .setDescription("Brightness of the outline colour.").setSection(SHADER);
    private final BooleanSetting shaderGlow = new BooleanSetting("Shader Glow", true)
            .setDescription("Adds a soft glow around each entity's outline that fades outward.").setSection(SHADER);
    private final SliderSetting glowRadius = new SliderSetting("Glow Radius", 10, 2, 40, 0)
            .setDescription("How far the glow spreads out from the outline, in pixels.").setSection(SHADER);
    private final SliderSetting glowAlpha = new SliderSetting("Glow Alpha", 0.65, 0.0, 1.0, 2)
            .setDescription("How opaque the glow is.").setSection(SHADER);
    private final SliderSetting glowIntensity = new SliderSetting("Glow Intensity", 1.2, 0.1, 3.0, 2)
            .setDescription("Brightness of the glow.").setSection(SHADER);

    // ---- Chams (the former Chams module) ----
    private final BooleanSetting chams = new BooleanSetting("Chams", false)
            .setDescription("Re-draws players and mobs in a flat colour through walls, following their live animation.").setSection(CHAMS);
    private final SliderSetting occludedOpacity = new SliderSetting("Occluded Opacity", 0.55, 0.05, 1.0, 2)
            .setDescription("Opacity of the chams colour drawn through walls.").setDependsOn("Chams").setSection(CHAMS);
    private final BooleanSetting visiblePass = new BooleanSetting("Tint Visible", true)
            .setDescription("Also tints the parts you can see directly, in Visible Color.").setDependsOn("Chams").setSection(CHAMS);
    private final ColorSetting visibleColor = new ColorSetting("Visible Color", new Color(255, 255, 255))
            .setDescription("Tint for the directly visible parts.").setDependsOn("Chams,Tint Visible").setSection(CHAMS);
    private final SliderSetting visibleOpacity = new SliderSetting("Visible Opacity", 0.18, 0.02, 1.0, 2)
            .setDescription("Opacity of the visible tint.").setDependsOn("Chams,Tint Visible").setSection(CHAMS);
    private final BooleanSetting glowing = new BooleanSetting("Chams Glowing", false)
            .setDescription("Additive blending: the chams colour adds light instead of covering, for a neon look.")
            .setDependsOn("Chams").setSection(CHAMS);
    private final SliderSetting glowLayers = new SliderSetting("Chams Glow Layers", 0, 0, 4, 0)
            .setDescription("Soft expanded shells around the model for a halo; 0 turns the halo off.")
            .setDependsOn("Chams").setSection(CHAMS);
    private final SliderSetting glowSize = new SliderSetting("Chams Glow Size", 1.08, 1.01, 1.25, 2)
            .setDescription("How far the outermost halo shell reaches, as a scale of the model.")
            .setDependsOn("Chams").setSection(CHAMS);

    // ---- 2D boxes (the former 2D ESP) ----
    private final ModeSetting box2d = new ModeSetting("2D Boxes", "Corners", "Off", "Corners", "Full")
            .setDescription("Screen-space boxes: Corners draws the four corner brackets; Full draws the whole rectangle.").setSection(BOX2D);
    private final SliderSetting lineWidth = new SliderSetting("2D Line Width", 1.6, 1.0, 3.0, 1)
            .setDescription("Line thickness of the 2D box in pixels.").setDependsOn("!2D Boxes:Off").setSection(BOX2D);
    private final BooleanSetting outline2d = new BooleanSetting("2D Outline", true)
            .setDescription("Dark border around every 2D line so boxes read on any background.").setDependsOn("!2D Boxes:Off").setSection(BOX2D);
    private final BooleanSetting boxFill2D = new BooleanSetting("2D Box Fill", true)
            .setDescription("Fills 2D boxes with a faint translucent gradient.").setDependsOn("!2D Boxes:Off").setSection(BOX2D);
    private final BooleanSetting healthBar = new BooleanSetting("Health Bar", true)
            .setDescription("Vertical health bar beside each 2D box; absorption is stacked on top.").setDependsOn("!2D Boxes:Off").setSection(BOX2D);
    private final BooleanSetting healthText = new BooleanSetting("Health Text", false)
            .setDescription("Health number next to the health bar.").setDependsOn("!2D Boxes:Off,Health Bar").setSection(BOX2D);
    private final BooleanSetting armorBar = new BooleanSetting("Armor Bar", false)
            .setDescription("Vertical armor-points bar on the right of each player's 2D box.").setDependsOn("!2D Boxes:Off").setSection(BOX2D);
    private final BooleanSetting distanceText = new BooleanSetting("Distance", false)
            .setDescription("Distance in blocks under the 2D box.").setDependsOn("!2D Boxes:Off").setSection(BOX2D);

    // ---- 3D boxes ----
    private final BooleanSetting box3d = new BooleanSetting("3D Boxes", false)
            .setDescription("Gradient-shaded boxes around entities in the world.").setSection(BOX3D);
    private final SliderSetting box3dFillAlpha = new SliderSetting("3D Fill Alpha", 0.22, 0.0, 0.65, 2)
            .setDescription("How opaque the fill of 3D boxes is.").setDependsOn("3D Boxes").setSection(BOX3D);
    private final SliderSetting boxStrength = new SliderSetting("3D Crystal Spread", 1.8, 0.5, 4.0, 1)
            .setDescription("Strength of the gradient wave across the 3D box around crystals.").setSection(BOX3D);
    private final BooleanSetting tracers = new BooleanSetting("Tracers", false)
            .setDescription("Draws a line from your view to each highlighted entity, through walls.").setSection(BOX3D);

    private final List<Entity> renderTargets = new ArrayList<>();

    public ShaderEsp() {
        super("ShaderESP", "Highlights players, mobs and end crystals so you can see them through walls, with any mix of styles: a shader pass that tints and outlines the models themselves, chams that re-draw the model in a flat colour, 2D boxes or corners with health and armor bars, 3D gradient boxes and tracers. Mobs are picked by kind and coloured per kind. Purely visual.", Category.RENDER);
        INSTANCE = this;
        for (var s : List.of(range, targetPlayers, includeFriends, targetSelf, invisibles, targetCrystals, throughWalls,
                hostile, passive, villagers, water, ambient, mobColorByKind, hostileColor, passiveColor, villagerColor,
                waterColor, ambientColor,
                mode, customColor, secondaryColor, friendColor, enemyColor,
                shader, shaderFill, fillStyle, fillAlpha, fillBrightness, gradientAngle, smokeScale, smokeSpeed,
                smokeOctaves, smokeContrast, shaderOutline, outlineWidth, outlineAlpha, outlineIntensity, shaderGlow,
                glowRadius, glowAlpha, glowIntensity,
                chams, occludedOpacity, visiblePass, visibleColor, visibleOpacity, glowing, glowLayers, glowSize,
                box2d, lineWidth, outline2d, boxFill2D, healthBar, healthText, armorBar, distanceText,
                box3d, box3dFillAlpha, boxStrength, tracers)) {
            addSetting(s);
        }

        shaderFill.setVisibility(shader::isEnabled);
        fillStyle.setVisibility(() -> shader.isEnabled() && shaderFill.isEnabled());
        fillAlpha.setVisibility(() -> shader.isEnabled() && shaderFill.isEnabled());
        fillBrightness.setVisibility(() -> shader.isEnabled() && shaderFill.isEnabled());
        gradientAngle.setVisibility(() -> shader.isEnabled() && shaderFill.isEnabled() && !fillStyle.is("Solid"));
        smokeScale.setVisibility(() -> shader.isEnabled() && shaderFill.isEnabled() && fillStyle.is("Smoke"));
        smokeSpeed.setVisibility(() -> shader.isEnabled() && shaderFill.isEnabled() && fillStyle.is("Smoke"));
        smokeOctaves.setVisibility(() -> shader.isEnabled() && shaderFill.isEnabled() && fillStyle.is("Smoke"));
        smokeContrast.setVisibility(() -> shader.isEnabled() && shaderFill.isEnabled() && fillStyle.is("Smoke"));
        shaderOutline.setVisibility(shader::isEnabled);
        outlineWidth.setVisibility(() -> shader.isEnabled() && shaderOutline.isEnabled());
        outlineAlpha.setVisibility(() -> shader.isEnabled() && shaderOutline.isEnabled());
        outlineIntensity.setVisibility(() -> shader.isEnabled() && shaderOutline.isEnabled());
        shaderGlow.setVisibility(shader::isEnabled);
        glowRadius.setVisibility(() -> shader.isEnabled() && shaderGlow.isEnabled());
        glowAlpha.setVisibility(() -> shader.isEnabled() && shaderGlow.isEnabled());
        glowIntensity.setVisibility(() -> shader.isEnabled() && shaderGlow.isEnabled());
        boxStrength.setVisibility(targetCrystals::isEnabled);
        // read in both crystal outline and 3D Boxes modes (see onWorldRender)
    }

    private boolean uses2D() {
        return !box2d.is("Off");
    }

    // =========================================================================
    // TARGET SELECTION
    // =========================================================================

    private MobKind mobKind(LivingEntity living) {
        if (living instanceof MerchantEntity) return MobKind.VILLAGER;
        return mobKind(living.getType());
    }

    private static MobKind mobKind(EntityType<?> type) {
        if (type == null) return MobKind.NONE;
        if (type == EntityType.VILLAGER || type == EntityType.WANDERING_TRADER) return MobKind.VILLAGER;
        return switch (type.getSpawnGroup()) {
            case MONSTER -> MobKind.HOSTILE;
            case CREATURE, AXOLOTLS -> MobKind.PASSIVE;
            case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE -> MobKind.WATER;
            case AMBIENT -> MobKind.AMBIENT;
            default -> MobKind.NONE;
        };
    }

    private boolean kindWanted(MobKind kind) {
        return switch (kind) {
            case HOSTILE -> hostile.isEnabled();
            case PASSIVE -> passive.isEnabled();
            case VILLAGER -> villagers.isEnabled();
            case WATER -> water.isEnabled();
            case AMBIENT -> ambient.isEnabled();
            case NONE -> false;
        };
    }

    private Color kindColor(MobKind kind) {
        return switch (kind) {
            case HOSTILE -> hostileColor.getValue();
            case PASSIVE -> passiveColor.getValue();
            case VILLAGER -> villagerColor.getValue();
            case WATER -> waterColor.getValue();
            case AMBIENT -> ambientColor.getValue();
            case NONE -> null;
        };
    }

    private boolean playerWanted(PlayerEntity player) {
        if (player == mc.player) return targetSelf.isEnabled() && !mc.options.getPerspective().isFirstPerson();
        if (!targetPlayers.isEnabled() || player.isSpectator()) return false;
        if (!includeFriends.isEnabled() && FriendManager.getInstance().isFriend(player.getUuid())) return false;
        return !isBot(player);
    }

    /** Whether this entity is drawn by any style under the current target settings. */
    private boolean isTarget(Entity entity) {
        if (entity == null || !entity.isAlive()) return false;
        if (entity instanceof PlayerEntity player) {
            if (!playerWanted(player)) return false;
        } else if (entity instanceof EndCrystalEntity) {
            if (!targetCrystals.isEnabled()) return false;
        } else if (entity instanceof LivingEntity living) {
            if (entity instanceof ArmorStandEntity || !kindWanted(mobKind(living))) return false;
        } else {
            return false;
        }
        if (entity.isInvisible() && !invisibles.isEnabled()) return false;
        if (entity != mc.player && mc.player.squaredDistanceTo(entity) > range.getValue() * range.getValue()) return false;
        return true;
    }

    private static boolean isBot(Entity entity) {
        List<BotFilterProvider> filters = ModuleAccess.getEnabledHooks(BotFilterProvider.class);
        for (int i = 0; i < filters.size(); i++) {
            try {
                if (filters.get(i).isBot(entity)) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @Override
    public void onTick() {
        renderTargets.clear();
        if (!isEnabled() || mc.world == null || mc.player == null) return;
        for (Entity entity : mc.world.getEntities()) {
            if (isTarget(entity)) renderTargets.add(entity);
        }
    }

    @Override
    public void onDisable() {
        renderTargets.clear();
    }

    // =========================================================================
    // SHADER PASS (mask via vanilla entity outline framebuffer, composited by ShaderEspEngine)
    // =========================================================================

    @Override
    public boolean shaderEspActive() {
        return isEnabled() && shader.isEnabled() && mc.world != null && mc.player != null;
    }

    @Override
    public boolean shaderEspWantsMask(Entity entity) {
        return mc.player != null && mc.world != null && isTarget(entity);
    }

    @Override
    public int shaderEspMaskColor(Entity entity, long nowMillis) {
        return resolveEntityColorInt(entity, nowMillis, entity.getId() * 45) & 0x00FFFFFF;
    }

    @Override
    public void shaderEspParameters(ShaderEspParams out, long nowMillis) {
        out.throughWalls = throughWalls.isEnabled();
        out.fill = shaderFill.isEnabled();
        out.fillAlpha = (float) fillAlpha.getValue();
        out.fillStyle = switch (fillStyle.getCurrentMode()) {
            case "Smoke" -> ShaderEspParams.FILL_SMOKE;
            case "Gradient" -> ShaderEspParams.FILL_GRADIENT;
            default -> ShaderEspParams.FILL_SOLID;
        };
        out.fillBrightness = (float) fillBrightness.getValue();
        out.outline = shaderOutline.isEnabled();
        out.outlineWidth = (float) outlineWidth.getValue();
        out.outlineAlpha = (float) outlineAlpha.getValue();
        out.outlineIntensity = (float) outlineIntensity.getValue();
        out.glow = shaderGlow.isEnabled();
        out.glowRadius = (float) glowRadius.getValue();
        out.glowAlpha = (float) glowAlpha.getValue();
        out.glowIntensity = (float) glowIntensity.getValue();
        out.smokeScale = (float) smokeScale.getValue();
        out.smokeSpeed = (float) smokeSpeed.getValue();
        out.smokeOctaves = (int) Math.round(smokeOctaves.getValue());
        out.smokeContrast = (float) smokeContrast.getValue();
        out.secondaryRgb = resolveSecondary(nowMillis).getRGB() & 0x00FFFFFF;
        out.gradientAngleRadians = (float) Math.toRadians(gradientAngle.getValue());
        out.friendRgb = friendColor.getValue().getRGB() & 0x00FFFFFF;
        out.enemyRgb = enemyColor.getValue().getRGB() & 0x00FFFFFF;
    }

    /** Gradient partner for the active palette; relation colors ignore it in the shader. */
    private Color resolveSecondary(long now) {
        return switch (mode.getCurrentMode()) {
            case "Spectrum" -> Color.getHSBColor((float) ((now * 0.00018 + 0.33) % 1.0), 0.82f, 1.0f);
            case "Pearl" -> new Color(237, 250, 255);
            case "Theme Sync" -> mix(AccentState.accentColor(), Color.WHITE, 0.35f);
            case "Dual Gradient", "Custom" -> secondaryColor.getValue();
            default -> new Color(125, 115, 255);
        };
    }

    // =========================================================================
    // CHAMS (model passes submitted by LivingEntityRendererMixin)
    // =========================================================================

    @Override
    public boolean chamsApplies(LivingEntityRenderState state) {
        if (!chams.isEnabled() || mc.player == null || mc.world == null || state == null) return false;
        if (state.invisible && !invisibles.isEnabled()) return false;
        if (state instanceof PlayerEntityRenderState ps) {
            if (ps.id == mc.player.getId()) return targetSelf.isEnabled();
            Entity entity = mc.world.getEntityById(ps.id);
            return entity instanceof PlayerEntity player && isTarget(player);
        }
        if (state.entityType == EntityType.ARMOR_STAND) return false;
        if (!kindWanted(mobKind(state.entityType))) return false;
        // The render state carries no entity id for mobs, so apply the same range limit from its position.
        double dx = state.x - mc.player.getX(), dy = state.y - mc.player.getY(), dz = state.z - mc.player.getZ();
        return dx * dx + dy * dy + dz * dz <= range.getValue() * range.getValue();
    }

    @Override
    public int chamsOccludedArgb(LivingEntityRenderState state) {
        long now = System.currentTimeMillis();
        int rgb;
        if (state instanceof PlayerEntityRenderState ps) {
            Entity entity = mc.world == null ? null : mc.world.getEntityById(ps.id);
            rgb = entity != null ? resolveEntityColorInt(entity, now, ps.id * 45) : resolveColor(now, ps.id * 45).getRGB();
        } else {
            Color kind = mobColorByKind.isEnabled() ? kindColor(mobKind(state.entityType)) : null;
            rgb = kind != null ? kind.getRGB() : resolveColor(now, 0).getRGB();
        }
        return argb(rgb, occludedOpacity.getValue());
    }

    @Override
    public int chamsVisibleArgb(LivingEntityRenderState state) {
        if (!visiblePass.isEnabled()) return 0;
        return argb(visibleColor.getValue().getRGB(), visibleOpacity.getValue());
    }

    @Override
    public boolean chamsAdditive() {
        return glowing.isEnabled();
    }

    @Override
    public int chamsGlowLayers() {
        return (int) Math.round(glowLayers.getValue());
    }

    @Override
    public float chamsGlowScale() {
        return (float) glowSize.getValue();
    }

    private static int argb(int rgb, double opacity) {
        int a = (int) Math.round(Math.max(0.0, Math.min(1.0, opacity)) * 255.0);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    // =========================================================================
    // 2D SCREEN-SPACE ESP (Full Box, Corner Brackets, Health / Absorption / Armor Bars)
    // =========================================================================

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (!isEnabled() || mc.world == null || mc.player == null || renderTargets.isEmpty() || !uses2D()) return;

        if (!Msdf.begin()) return;
        try {
            int screenW = Msdf.width();
            int screenH = Msdf.height();
            Matrix4f viewProj = WorldProjector.viewProjection(tickDelta);
            long now = System.currentTimeMillis();
            boolean cornersMode = box2d.is("Corners");
            float t = (float) lineWidth.getValue();
            boolean shadow = outline2d.isEnabled();

            for (Entity entity : renderTargets) {
                if (entity == null || !entity.isAlive()) continue;

                Box box = interpolateEntityBox(entity, tickDelta);
                WorldProjector.Projected proj = WorldProjector.project(viewProj, box, screenW, screenH);
                if (!proj.visible()) continue;

                float x1 = proj.minX();
                float y1 = proj.minY();
                float x2 = proj.maxX();
                float y2 = proj.maxY();
                float bw = x2 - x1;
                float bh = y2 - y1;
                if (bw < 2.0f || bh < 2.0f || bw > screenW * 1.5f || bh > screenH * 1.5f) continue;

                int[] cornerColors = resolveCornerColors(entity, now);

                if (boxFill2D.isEnabled()) {
                    int fillArgb = replAlpha(blendColorsInt(cornerColors[0], cornerColors[2], 0.5f), 28);
                    Msdf.rect(x1 + 1.0f, y1 + 1.0f, bw - 2.0f, bh - 2.0f, 0.0f, fillArgb);
                }

                if (cornersMode) {
                    draw2DCorners(x1, y1, x2, y2, cornerColors, t, shadow);
                } else {
                    draw2DFullBox(x1, y1, x2, y2, cornerColors, t, shadow);
                }

                if (entity instanceof LivingEntity living) {
                    if (healthBar.isEnabled()) draw2DHealthBar(x1, y1, y2, living);
                    if (armorBar.isEnabled() && entity instanceof PlayerEntity) draw2DArmorBar(x2, y1, y2, living);
                }
                if (distanceText.isEnabled()) {
                    int meters = (int) Math.round(Math.sqrt(mc.player.squaredDistanceTo(entity)));
                    drawSmallLabel(meters + "m", proj.centerX(), y2 + 3.0f);
                }
            }
        } finally {
            Msdf.end();
        }
    }

    private void draw2DFullBox(float x1, float y1, float x2, float y2, int[] c, float t, boolean shadow) {
        float w = x2 - x1;
        float h = y2 - y1;
        int outline = 0xC8000000;

        if (shadow) {
            // Outer and inner crisp dark shadow frames
            drawRectOutline(x1 - 1.0f, y1 - 1.0f, w + 2.0f, h + 2.0f, 1.0f, outline);
            if (w > 4.0f && h > 4.0f) {
                drawRectOutline(x1 + t, y1 + t, w - t * 2.0f, h - t * 2.0f, 1.0f, outline);
            }
        }

        // Sub-segmented 4-edge gradient stroke (top, bottom, left, right)
        int segs = 6;
        float stepX = w / segs;
        float stepY = h / segs;
        for (int i = 0; i < segs; i++) {
            float frac = (i + 0.5f) / segs;
            int topCol = blendColorsInt(c[0], c[1], frac);
            int botCol = blendColorsInt(c[3], c[2], frac);
            int leftCol = blendColorsInt(c[0], c[3], frac);
            int rightCol = blendColorsInt(c[1], c[2], frac);

            float sx = x1 + i * stepX;
            float sy = y1 + i * stepY;
            Msdf.rect(sx, y1, stepX + 0.5f, t, 0.0f, topCol);
            Msdf.rect(sx, y2 - t, stepX + 0.5f, t, 0.0f, botCol);
            Msdf.rect(x1, sy, t, stepY + 0.5f, 0.0f, leftCol);
            Msdf.rect(x2 - t, sy, t, stepY + 0.5f, 0.0f, rightCol);
        }
    }

    private void draw2DCorners(float x1, float y1, float x2, float y2, int[] c, float t, boolean shadow) {
        float w = x2 - x1;
        float h = y2 - y1;
        float cw = Math.max(3.5f, Math.min(w * CORNER_SEGMENT_FRACTION, 24.0f));
        float ch = Math.max(3.5f, Math.min(h * CORNER_SEGMENT_FRACTION, 24.0f));
        int outline = 0xD0000000;

        if (shadow) {
            Msdf.rect(x1 - 1.0f, y1 - 1.0f, cw + 2.0f, t + 2.0f, 0.5f, outline);
            Msdf.rect(x1 - 1.0f, y1 - 1.0f, t + 2.0f, ch + 2.0f, 0.5f, outline);
            Msdf.rect(x2 - cw - 1.0f, y1 - 1.0f, cw + 2.0f, t + 2.0f, 0.5f, outline);
            Msdf.rect(x2 - t - 1.0f, y1 - 1.0f, t + 2.0f, ch + 2.0f, 0.5f, outline);
            Msdf.rect(x2 - cw - 1.0f, y2 - t - 1.0f, cw + 2.0f, t + 2.0f, 0.5f, outline);
            Msdf.rect(x2 - t - 1.0f, y2 - ch - 1.0f, t + 2.0f, ch + 2.0f, 0.5f, outline);
            Msdf.rect(x1 - 1.0f, y2 - t - 1.0f, cw + 2.0f, t + 2.0f, 0.5f, outline);
            Msdf.rect(x1 - 1.0f, y2 - ch - 1.0f, t + 2.0f, ch + 2.0f, 0.5f, outline);
        }

        // Top-Left, Top-Right, Bottom-Right, Bottom-Left brackets
        Msdf.rect(x1, y1, cw, t, 0.0f, c[0]);
        Msdf.rect(x1, y1, t, ch, 0.0f, c[0]);
        Msdf.rect(x2 - cw, y1, cw, t, 0.0f, c[1]);
        Msdf.rect(x2 - t, y1, t, ch, 0.0f, c[1]);
        Msdf.rect(x2 - cw, y2 - t, cw, t, 0.0f, c[2]);
        Msdf.rect(x2 - t, y2 - ch, t, ch, 0.0f, c[2]);
        Msdf.rect(x1, y2 - t, cw, t, 0.0f, c[3]);
        Msdf.rect(x1, y2 - ch, t, ch, 0.0f, c[3]);
    }

    private void draw2DHealthBar(float x1, float y1, float y2, LivingEntity living) {
        float maxHp = Math.max(1.0f, living.getMaxHealth());
        float hp = MathHelper.clamp(living.getHealth(), 0.0f, maxHp);
        float absorption = Math.max(0.0f, living.getAbsorptionAmount());

        float hpRatio = MathHelper.clamp(hp / maxHp, 0.0f, 1.0f);
        float absRatio = MathHelper.clamp(absorption / maxHp, 0.0f, 1.0f);

        float barW = 2.6f;
        float barX = x1 - barW - 4.0f;
        float barH = y2 - y1;

        // Dark background track with border
        Msdf.rect(barX - 1.0f, y1 - 1.0f, barW + 2.0f, barH + 2.0f, 1.0f, 0xC8080A10);

        // Health fill from bottom up (smooth red -> yellow -> emerald green)
        float fillH = barH * hpRatio;
        if (fillH > 0.5f) {
            int hpColorTop = Color.HSBtoRGB(hpRatio * 0.33f, 0.88f, 1.0f) | 0xFF000000;
            int hpColorBot = Color.HSBtoRGB(Math.max(0.0f, hpRatio * 0.33f - 0.08f), 0.92f, 0.85f) | 0xFF000000;
            int segs = 4;
            float segH = fillH / segs;
            float startY = y2 - fillH;
            for (int i = 0; i < segs; i++) {
                float t = (float) i / Math.max(1, segs - 1);
                int col = blendColorsInt(hpColorTop, hpColorBot, t);
                Msdf.rect(barX, startY + i * segH, barW, segH + 0.4f, 0.0f, col);
            }
        }

        // Absorption overlay bar in electric cyan (#3AA7FF)
        if (absRatio > 0.01f) {
            float absH = barH * absRatio;
            Msdf.rect(barX, y2 - absH, barW, absH, 0.0f, 0xDC3AA7FF);
        }

        if (healthText.isEnabled()) {
            int value = Math.round(hp + absorption);
            MsdfFont font = Msdf.semiBold();
            if (font != null) {
                String s = Integer.toString(value);
                float size = 9.0f;
                float tw = Msdf.width(font, s, size);
                float top = Math.max(y1, y2 - fillH - 4.0f);
                Msdf.textWithShadow(font, s, barX - 3.0f - tw, top, size, 0xFFF2F6FF);
            }
        }
    }

    private static void draw2DArmorBar(float x2, float y1, float y2, LivingEntity living) {
        float barW = 2.6f;
        float barX = x2 + 4.0f;
        float barH = y2 - y1;
        float armor = MathHelper.clamp(living.getArmor() / 20.0f, 0.0f, 1.0f);
        Msdf.rect(barX - 1.0f, y1 - 1.0f, barW + 2.0f, barH + 2.0f, 1.0f, 0xC8080A10);
        if (armor > 0.01f) {
            float h = barH * armor;
            Msdf.rect(barX, y2 - h, barW, h, 0.0f, 0xFF5FA8FF);
        }
    }

    private static void drawSmallLabel(String label, float centerX, float topY) {
        MsdfFont font = Msdf.semiBold();
        if (font == null) return;
        float size = 9.5f;
        float tw = Msdf.width(font, label, size);
        Msdf.textWithShadow(font, label, centerX - tw * 0.5f, topY, size, 0xFFF2F6FF);
    }

    private static void drawRectOutline(float x, float y, float w, float h, float t, int argb) {
        Msdf.rect(x, y, w, t, 0.0f, argb);
        Msdf.rect(x, y + h - t, w, t, 0.0f, argb);
        Msdf.rect(x, y, t, h, 0.0f, argb);
        Msdf.rect(x + w - t, y, t, h, 0.0f, argb);
    }

    // =========================================================================
    // 3D WORLD-SPACE ESP (8-Corner Wave Gradient Boxes, Crystal Octagrams, Tracers)
    // =========================================================================

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (!isEnabled() || mc.world == null || mc.player == null || renderTargets.isEmpty()) return;
        boolean drawBoxes = box3d.isEnabled();
        boolean drawTracers = tracers.isEnabled();
        boolean drawCrystals = targetCrystals.isEnabled() && !drawBoxes;
        if (!drawBoxes && !drawTracers && !drawCrystals) return;
        Camera camera = mc.gameRenderer.getCamera();
        if (camera == null) return;
        Vec3d cameraPos = camera.getCameraPos();
        long now = System.currentTimeMillis();
        float strength = (float) boxStrength.getValue();
        boolean seeThrough = throughWalls.isEnabled();
        Vec3d tracerFrom = drawTracers ? mc.player.getRotationVec(tickDelta).multiply(0.4) : null;

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(16384)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            for (Entity entity : renderTargets) {
                if (entity == null || !entity.isAlive() || entity == mc.player) continue;
                double x = MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()) - cameraPos.x;
                double y = MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()) - cameraPos.y;
                double z = MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ()) - cameraPos.z;

                if (entity instanceof EndCrystalEntity crystal && !drawBoxes) {
                    matrices.push();
                    matrices.translate(x, y, z);
                    Color color = resolveColor(now, crystal.getId() * 45);
                    renderCrystal(matrices, immediate, crystal, tickDelta, color, strength, seeThrough);
                    matrices.pop();
                } else if (drawBoxes) {
                    Box localBox = entity.getBoundingBox().offset(-entity.getX(), -entity.getY(), -entity.getZ())
                            .expand(0.04 + (entity instanceof EndCrystalEntity ? 0.012 * strength : 0.0));
                    matrices.push();
                    matrices.translate(x, y, z);
                    render3DGradientBox(matrices, immediate, entity, localBox, now, seeThrough);
                    matrices.pop();
                }

                if (drawTracers) {
                    Box bb = entity.getBoundingBox();
                    float cx = (float) x;
                    float cy = (float) (y + (bb.maxY - bb.minY) * 0.5);
                    float cz = (float) z;
                    int argb = replAlpha(resolveEntityColorInt(entity, now, entity.getId() * 45), 230);
                    VertexConsumer lineBuf = immediate.getBuffer(ShaderRenderLayers.getEspLineLayer(true));
                    Matrix4f mat = matrices.peek().getPositionMatrix();
                    emitGradientEdge(lineBuf, mat, (float) tracerFrom.x, (float) tracerFrom.y, (float) tracerFrom.z, argb,
                            cx, cy, cz, argb);
                }
            }
            immediate.draw();
        }
    }

    /**
     * Renders Combatant's 8-corner wave-gradient 3D Box ESP:
     * 6 translucent gradient-shaded quads + 12 crisp per-vertex gradient wireframe edges.
     */
    private void render3DGradientBox(MatrixStack matrices, VertexConsumerProvider immediate,
                                     Entity entity, Box box, long now, boolean seeThrough) {
        int[] c = new int[8];
        int baseSeed = entity.getId() * 35;
        for (int i = 0; i < 8; i++) {
            c[i] = resolveEntityColorInt(entity, now, baseSeed + i * 45);
        }

        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;

        Matrix4f mat = matrices.peek().getPositionMatrix();
        float fillAlpha = (float) box3dFillAlpha.getValue();

        // 1. Translucent 8-corner gradient shaded faces
        if (fillAlpha > 0.01f) {
            VertexConsumer quadBuf = immediate.getBuffer(ShaderRenderLayers.getEspLayer(seeThrough));
            int a = MathHelper.clamp(Math.round(fillAlpha * 255.0f), 0, 255);
            int c0 = replAlpha(c[0], a);
            int c1 = replAlpha(c[1], a);
            int c2 = replAlpha(c[2], a);
            int c3 = replAlpha(c[3], a);
            int c4 = replAlpha(c[4], a);
            int c5 = replAlpha(c[5], a);
            int c6 = replAlpha(c[6], a);
            int c7 = replAlpha(c[7], a);

            emitGradientQuad(quadBuf, mat, minX, minY, minZ, c0, maxX, minY, minZ, c1, maxX, minY, maxZ, c2, minX, minY, maxZ, c3);
            emitGradientQuad(quadBuf, mat, minX, maxY, minZ, c4, minX, maxY, maxZ, c7, maxX, maxY, maxZ, c6, maxX, maxY, minZ, c5);
            emitGradientQuad(quadBuf, mat, minX, minY, minZ, c0, minX, maxY, minZ, c4, maxX, maxY, minZ, c5, maxX, minY, minZ, c1);
            emitGradientQuad(quadBuf, mat, minX, minY, maxZ, c3, maxX, minY, maxZ, c2, maxX, maxY, maxZ, c6, minX, maxY, maxZ, c7);
            emitGradientQuad(quadBuf, mat, minX, minY, minZ, c0, minX, minY, maxZ, c3, minX, maxY, maxZ, c7, minX, maxY, minZ, c4);
            emitGradientQuad(quadBuf, mat, maxX, minY, minZ, c1, maxX, maxY, minZ, c5, maxX, maxY, maxZ, c6, maxX, minY, maxZ, c2);
        }

        // 2. 12-Edge animated gradient wireframe outline
        VertexConsumer lineBuf = immediate.getBuffer(ShaderRenderLayers.getEspLineLayer(seeThrough));
        int lineAlpha = 235;
        int l0 = replAlpha(c[0], lineAlpha);
        int l1 = replAlpha(c[1], lineAlpha);
        int l2 = replAlpha(c[2], lineAlpha);
        int l3 = replAlpha(c[3], lineAlpha);
        int l4 = replAlpha(c[4], lineAlpha);
        int l5 = replAlpha(c[5], lineAlpha);
        int l6 = replAlpha(c[6], lineAlpha);
        int l7 = replAlpha(c[7], lineAlpha);

        emitGradientEdge(lineBuf, mat, minX, minY, minZ, l0, maxX, minY, minZ, l1);
        emitGradientEdge(lineBuf, mat, maxX, minY, minZ, l1, maxX, minY, maxZ, l2);
        emitGradientEdge(lineBuf, mat, maxX, minY, maxZ, l2, minX, minY, maxZ, l3);
        emitGradientEdge(lineBuf, mat, minX, minY, maxZ, l3, minX, minY, minZ, l0);
        emitGradientEdge(lineBuf, mat, minX, maxY, minZ, l4, maxX, maxY, minZ, l5);
        emitGradientEdge(lineBuf, mat, maxX, maxY, minZ, l5, maxX, maxY, maxZ, l6);
        emitGradientEdge(lineBuf, mat, maxX, maxY, maxZ, l6, minX, maxY, maxZ, l7);
        emitGradientEdge(lineBuf, mat, minX, maxY, maxZ, l7, minX, maxY, minZ, l4);
        emitGradientEdge(lineBuf, mat, minX, minY, minZ, l0, minX, maxY, minZ, l4);
        emitGradientEdge(lineBuf, mat, maxX, minY, minZ, l1, maxX, maxY, minZ, l5);
        emitGradientEdge(lineBuf, mat, maxX, minY, maxZ, l2, maxX, maxY, maxZ, l6);
        emitGradientEdge(lineBuf, mat, minX, minY, maxZ, l3, minX, maxY, maxZ, l7);
    }

    private static void emitGradientQuad(VertexConsumer buf, Matrix4f mat,
                                         float x1, float y1, float z1, int c1,
                                         float x2, float y2, float z2, int c2,
                                         float x3, float y3, float z3, int c3,
                                         float x4, float y4, float z4, int c4) {
        buf.vertex(mat, x1, y1, z1).color(c1);
        buf.vertex(mat, x2, y2, z2).color(c2);
        buf.vertex(mat, x3, y3, z3).color(c3);

        buf.vertex(mat, x1, y1, z1).color(c1);
        buf.vertex(mat, x3, y3, z3).color(c3);
        buf.vertex(mat, x4, y4, z4).color(c4);
    }

    private static void emitGradientEdge(VertexConsumer buf, Matrix4f mat,
                                         float x1, float y1, float z1, int c1,
                                         float x2, float y2, float z2, int c2) {
        buf.vertex(mat, x1, y1, z1).color(c1);
        buf.vertex(mat, x2, y2, z2).color(c2);
    }

    private void renderCrystal(MatrixStack matrices, VertexConsumerProvider immediate, EndCrystalEntity crystal,
                               float tickDelta, Color color, float strength, boolean seeThrough) {
        float age = crystal.endCrystalAge + tickDelta;
        matrices.push();
        matrices.translate(0.0, MathHelper.sin(age * 0.2f) * 0.15f + 0.65f, 0.0);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(age * 3.0f));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(60.0f));
        drawBounds(matrices, immediate, new Box(-0.38, -0.38, -0.38, 0.38, 0.38, 0.38), color, strength, seeThrough);
        matrices.pop();
    }

    private void drawBounds(MatrixStack matrices, VertexConsumerProvider immediate, Box bounds, Color color, float strength, boolean seeThrough) {
        int layers = 3;
        for (int i = layers; i >= 1; i--) {
            float expand = 0.018f * i * strength;
            RenderUtils.drawBoxOutlineEsp(matrices, immediate, bounds.expand(expand), color, 0.16f / i, seeThrough);
        }
        RenderUtils.drawBoxOutlineEsp(matrices, immediate, bounds, color, 0.92f, seeThrough);
    }

    private static Box interpolateEntityBox(Entity entity, float tickDelta) {
        double ix = MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX());
        double iy = MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY());
        double iz = MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ());
        return entity.getBoundingBox().offset(ix - entity.getX(), iy - entity.getY(), iz - entity.getZ());
    }

    // =========================================================================
    // COLOURS
    // =========================================================================

    private int[] resolveCornerColors(Entity entity, long now) {
        int baseSeed = entity.getId() * 35;
        return new int[]{
                resolveEntityColorInt(entity, now, baseSeed),
                resolveEntityColorInt(entity, now, baseSeed + 90),
                resolveEntityColorInt(entity, now, baseSeed + 180),
                resolveEntityColorInt(entity, now, baseSeed + 270)
        };
    }

    private int resolveEntityColorInt(Entity entity, long now, int seed) {
        if (entity instanceof PlayerEntity player) {
            if (FriendManager.getInstance().isFriend(player.getUuid())) return friendColor.getValue().getRGB();
            if (EnemyManager.getInstance().isEnemy(player.getUuid())) return enemyColor.getValue().getRGB();
        } else if (mobColorByKind.isEnabled() && entity instanceof LivingEntity living
                && !(entity instanceof EndCrystalEntity)) {
            Color kind = kindColor(mobKind(living));
            if (kind != null) return kind.getRGB();
        }
        return resolveColor(now, seed).getRGB();
    }

    private Color resolveColor(long now, int seed) {
        float wave = 0.5f + 0.5f * MathHelper.sin((float) (now * 0.0022 + seed * 0.045));
        Color base = switch (mode.getCurrentMode()) {
            case "Spectrum" -> Color.getHSBColor((float) ((now * 0.00018 + seed * 0.0025) % 1.0), 0.82f, 1.0f);
            case "Pearl" -> mix(new Color(85, 213, 238), new Color(237, 250, 255),
                    0.36f + 0.25f * MathHelper.sin((float) (now * 0.0018 + seed * 0.04)));
            case "Dual Gradient" -> mix(customColor.getValue(), secondaryColor.getValue(), wave);
            case "Theme Sync" -> AccentState.accentColor();
            case "Custom" -> customColor.getValue();
            default -> mix(mix(new Color(0, 215, 235), new Color(125, 115, 255), wave),
                    new Color(195, 250, 255), 0.14f);
        };
        return base == null ? new Color(0, 220, 255) : base;
    }

    private static Color mix(Color a, Color b, float amount) {
        float t = MathHelper.clamp(amount, 0.0f, 1.0f);
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static int replAlpha(int color, int alpha) {
        return (MathHelper.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private static int blendColorsInt(int c1, int c2, float t) {
        float f = MathHelper.clamp(t, 0.0f, 1.0f);
        int a1 = (c1 >>> 24) & 0xFF, r1 = (c1 >>> 16) & 0xFF, g1 = (c1 >>> 8) & 0xFF, b1 = c1 & 0xFF;
        int a2 = (c2 >>> 24) & 0xFF, r2 = (c2 >>> 16) & 0xFF, g2 = (c2 >>> 8) & 0xFF, b2 = c2 & 0xFF;
        int a = Math.round(a1 + (a2 - a1) * f);
        int r = Math.round(r1 + (r2 - r1) * f);
        int g = Math.round(g1 + (g2 - g1) * f);
        int b = Math.round(b1 + (b2 - b1) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
