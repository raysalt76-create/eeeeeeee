package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.hud.TotemPopManager;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.item.ItemStack;
import interstellarTikiTiks.apexx.client.render.msdf.Msdf;
import interstellarTikiTiks.apexx.client.render.msdf.MsdfFont;
import interstellarTikiTiks.apexx.client.render.msdf.WorldProjector;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

import java.awt.Color;

/**
 * Nametags, drawn with the MSDF renderer.
 *
 * <p>Two things changed versus the previous implementation, and both mattered:
 *
 * <ul>
 *   <li><b>Text is no longer Minecraft's bitmap font.</b> It is a signed-distance-field atlas, so
 *       it stays sharp at any scale instead of turning into stepped pixels when a tag scales up
 *       close or down at range.</li>
 *   <li><b>The projection is Minecraft's own.</b> The old code hand-built a perspective matrix
 *       from FOV and aspect and multiplied it in an order that only tested {@code w <= 0} â€” so
 *       tags could vanish or fly off screen. {@link WorldProjector} rebuilds the exact matrices
 *       the game uses and clips all twelve box edges at the near plane.</li>
 * </ul>
 *
 * <p>Implements {@code NametagHideProvider} so the vanilla-nametag suppression is reached through
 * a hook interface rather than a direct class reference â€” that decoupling is what keeps the mixin
 * from having to load this class, which was causing classloader errors under the thick loader.
 */
public class Nametags extends Module implements interstellarTikiTiks.apexx.client.module.hooks.NametagHideProvider {

    public static Nametags INSTANCE;

    private final SliderSetting maxDistance = new SliderSetting("Range", 64, 8, 256, 0)
            .setDescription("Farthest a player can be, in blocks, and still get an Apexx nametag.");
    private final SliderSetting scale = new SliderSetting("Scale", 100, 50, 250, 0)
            .setDescription("Tag size in percent. Tags are drawn with a sharp vector font, so they stay crisp at any size.");
    private final BooleanSetting dynamicScale = new BooleanSetting("Distance Scaling", true)
            .setDescription("Makes tags of far-away players smaller, with a minimum size so they never become unreadable.");

    private final BooleanSetting showHealth = new BooleanSetting("Health", true)
            .setDescription("Shows the player's current health as a number on the tag.");
    private final BooleanSetting healthBar = new BooleanSetting("Health Bar", true)
            .setDescription("Draws a coloured health bar under the name.");
    private final SliderSetting healthBarThickness = new SliderSetting("Health Bar Thickness", 100, 50, 300, 0)
            .setDescription("Height of the health bar in percent of its default thickness.");
    private final BooleanSetting customHealthColors = new BooleanSetting("Custom Health Colors", false)
            .setDescription("Colours the health bar by blending the two colours below instead of the default red-to-green sweep.");
    private final ColorSetting healthFullColor = new ColorSetting("Health Full Color", new Color(0, 255, 70))
            .setDescription("Health bar colour at full health.");
    private final ColorSetting healthEmptyColor = new ColorSetting("Health Empty Color", new Color(255, 40, 40))
            .setDescription("Health bar colour near zero health.");
    private final ColorSetting healthBarTrackColor = new ColorSetting("Health Bar Track", new Color(16, 20, 24, 128))
            .setDescription("Colour of the empty part of the health bar.");
    private final BooleanSetting showDistance = new BooleanSetting("Distance", false)
            .setDescription("Shows how many blocks away the player is.");
    private final ModeSetting healthBarPosition = new ModeSetting("Health Bar Position", "Above", "Above", "Below")
            .setDescription("Above: a slim bar floating over the name. Below: attached under the name card.")
            .setDependsOn("Health Bar");
    private final BooleanSetting absorptionBar = new BooleanSetting("Absorption", true)
            .setDescription("Shows absorption hearts as a gold segment after the health in the bar.")
            .setDependsOn("Health Bar");
    private final BooleanSetting showPing = new BooleanSetting("Ping", false)
            .setDescription("Shows the player's latency from the tab list.");
    private final BooleanSetting showHeldItem = new BooleanSetting("Held Item", true)
            .setDescription("Shows an icon of the item the player is holding above the tag.");
    private final BooleanSetting showTotemPops = new BooleanSetting("Totem Pops", true)
            .setDescription("Shows a gold badge such as x3 with how many totems the player has popped this fight "
                    + "(resets when they die or you change world).");
    private final ColorSetting totemBadgeColor = new ColorSetting("Totem Badge Color", new Color(255, 196, 40))
            .setDescription("Colour of the totem pop badge.")
            .setDependsOn("Totem Pops");

    private final BooleanSetting background = new BooleanSetting("Background", true)
            .setDescription("Draws a rounded card behind the tag text so it stays readable on any backdrop.");
    private final ColorSetting bgColor = new ColorSetting("Background Color", new Color(0x0A, 0x0E, 0x12))
            .setDescription("Colour of the tag card; its transparency comes from Background Opacity.");
    private final SliderSetting bgOpacity = new SliderSetting("Background Opacity", 0.72, 0.0, 1.0, 2)
            .setDescription("How opaque the tag card is, from 0 (invisible) to 1 (solid).");
    private final SliderSetting rounding = new SliderSetting("Rounding", 3, 0, 10, 0)
            .setDescription("Corner radius of the tag card; 0 gives square corners.");
    private final BooleanSetting outline = new BooleanSetting("Outline", false)
            .setDescription("Draws a thin border around the tag card.");
    private final ColorSetting outlineColor = new ColorSetting("Outline Color", new Color(0, 0, 0, 180))
            .setDescription("Colour of the tag card border (when Outline is on).");
    private final SliderSetting outlineThickness = new SliderSetting("Outline Thickness", 1.0, 0.5, 4.0, 1)
            .setDescription("Width of the tag card border, in pixels.");
    private final BooleanSetting textShadow = new BooleanSetting("Text Shadow", true)
            .setDescription("Draws a soft shadow under the tag text for contrast.");

    private final BooleanSetting themeAccent = new BooleanSetting("Theme Accent Text", true)
            .setDescription("Colours names with the current ClickGUI theme accent instead of Text Color.");
    private final ColorSetting textColor = new ColorSetting("Text Color", Color.WHITE)
            .setDescription("Name colour when Theme Accent Text is off.");

    private final BooleanSetting ignoreFriends = new BooleanSetting("Ignore Friends", true)
            .setDescription("Skips players on your friends list.");
    private final BooleanSetting renderVanillaNametag = new BooleanSetting("Render Vanilla Nametag", false)
            .setDescription("Also keeps Minecraft's own nametag above the player instead of replacing it.");

    public Nametags() {
        super("Nametags", "Draws clean, readable tags above players with their name, health and health bar, and optionally distance, visible from up to Range blocks and styled to match the client theme. Purely visual.", Category.RENDER);
        INSTANCE = this;
        addSetting(maxDistance);
        addSetting(scale);
        addSetting(dynamicScale);
        addSetting(showHealth);
        addSetting(healthBar);
        addSetting(healthBarThickness);
        addSetting(customHealthColors);
        addSetting(healthFullColor);
        addSetting(healthEmptyColor);
        addSetting(healthBarTrackColor);
        addSetting(showDistance);
        addSetting(healthBarPosition);
        addSetting(absorptionBar);
        addSetting(showPing);
        addSetting(showHeldItem);
        addSetting(showTotemPops);
        addSetting(totemBadgeColor);
        addSetting(background);
        addSetting(bgColor);
        addSetting(bgOpacity);
        addSetting(rounding);
        addSetting(outline);
        addSetting(outlineColor);
        addSetting(outlineThickness);
        addSetting(textShadow);
        addSetting(themeAccent);
        addSetting(textColor);
        addSetting(ignoreFriends);
        addSetting(renderVanillaNametag);
    }

    @Override
    public boolean shouldHideVanillaNametag() {
        return isEnabled() && !renderVanillaNametag.isEnabled();
    }

    public static boolean shouldHideVanillaNametagStatic() {
        return INSTANCE != null && INSTANCE.isEnabled() && !INSTANCE.renderVanillaNametag.isEnabled();
    }

    public BooleanSetting getRenderVanillaNametag() {
        return renderVanillaNametag;
    }

    @Override
    public void onRender(DrawContext context, float unusedTickDelta) {
        if (mc.world == null || mc.player == null) return;
        if (!Msdf.ready()) return;

        float tickDelta = mc.getRenderTickCounter().getTickProgress(true);
        int screenW = Msdf.width();
        int screenH = Msdf.height();
        if (screenW <= 0 || screenH <= 0) return;

        Matrix4f viewProj = WorldProjector.viewProjection(tickDelta);
        MsdfFont font = Msdf.semiBold();
        if (font == null) return;

        double maxDistSq = maxDistance.getValue() * maxDistance.getValue();
        // Framebuffer pixels are ~2x GUI units on a typical scale, so a base size in that space
        // keeps the tag visually similar to the old one while gaining the sharpness.
        float baseSize = (float) (scale.getValue() / 100.0) * 17.0f;

        if (!Msdf.begin()) return;
        try {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player && mc.options.getPerspective().isFirstPerson()) continue;
                if (!player.isAlive() || player.isSpectator()) continue;
                if (ignoreFriends.isEnabled()
                        && FriendManager.getInstance().isFriend(player.getUuid())) continue;

                double distSq = mc.player.squaredDistanceTo(player);
                if (distSq > maxDistSq) continue;
                double distance = Math.sqrt(distSq);

                // A zero-width box at the head: we only want the projected screen POINT, but
                // routing it through the same 12-edge clipper gives correct behaviour when the
                // player is partially behind the camera.
                double topY = player.getY() + player.getHeight() + 0.45;
                Box head = new Box(player.getX(), topY, player.getZ(), player.getX(), topY, player.getZ());
                WorldProjector.Projected p = WorldProjector.project(viewProj, head, screenW, screenH);
                if (!p.visible()) continue;

                float size = baseSize;
                if (dynamicScale.isEnabled()) {
                    // Floor at 55% so distant tags shrink but stay legible.
                    float factor = (float) MathHelper.clamp(12.0 / Math.max(distance, 1.0), 0.55, 1.35);
                    size *= factor;
                }

                drawTag(font, player, p.centerX(), p.minY(), size, (float) distance);
            }
        } finally {
            Msdf.end();
        }
        flushItemIcons(context);
    }

    /**
     * Held-item icons are vanilla item renders, so they go through the DrawContext after the MSDF batch:
     * they land on top of the tag cards. Positions were recorded in framebuffer pixels by drawTag.
     */
    private void flushItemIcons(DrawContext context) {
        int count = itemCount;
        itemCount = 0;
        if (count == 0 || context == null || mc.getWindow() == null) return;
        float guiScale = Math.max(1, mc.getWindow().getScaleFactor());
        var m = context.getMatrices();
        for (int i = 0; i < count; i++) {
            ItemStack stack = itemStacks[i];
            itemStacks[i] = null;
            if (stack == null || stack.isEmpty()) continue;
            float s = Math.max(0.5f, Math.min(1.25f, itemSizes[i] / guiScale / 16.0f));
            m.pushMatrix();
            m.translate(itemX[i] / guiScale, itemY[i] / guiScale);
            m.scale(s, s);
            context.drawItem(stack, -8, -16);
            m.popMatrix();
        }
    }

    private static final int MAX_ITEM_ICONS = 64;
    private final ItemStack[] itemStacks = new ItemStack[MAX_ITEM_ICONS];
    private final float[] itemX = new float[MAX_ITEM_ICONS];
    private final float[] itemY = new float[MAX_ITEM_ICONS];
    private final float[] itemSizes = new float[MAX_ITEM_ICONS];
    private int itemCount;

    private void drawTag(MsdfFont font, PlayerEntity player, float cx, float cy, float size, float distance) {
        StringBuilder sb = new StringBuilder(player.getName().getString());
        if (showHealth.isEnabled()) {
            sb.append("  ").append((int) (player.getHealth() + player.getAbsorptionAmount()));
        }
        if (showDistance.isEnabled()) {
            sb.append("  ").append(Math.round(distance)).append('m');
        }
        if (showPing.isEnabled() && mc.getNetworkHandler() != null) {
            PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
            if (entry != null) sb.append("  ").append(entry.getLatency()).append("ms");
        }
        String label = sb.toString();

        float textW = font.getWidth(label, size);
        float textH = font.getTextHeight(size);
        float padX = size * 0.40f;
        float padY = size * 0.22f;

        float boxW = textW + padX * 2.0f;
        float boxH = textH + padY * 2.0f;
        float boxX = cx - boxW * 0.5f;
        float boxY = cy - boxH;

        float barThickness = Math.max(1.5f, size * 0.11f * (float) (healthBarThickness.getValue() / 100.0));
        boolean barAbove = healthBar.isEnabled() && healthBarPosition.is("Above");
        float healthBarH = healthBar.isEnabled() && !barAbove ? barThickness : 0.0f;
        float totalH = boxH + healthBarH;
        // Top of the whole tag assembly, for anything stacked above it (floating bar, item icon).
        float top = boxY;
        if (barAbove) {
            float gap = Math.max(1.0f, size * 0.14f);
            float barW = boxW * 0.82f;
            float barX = cx - barW * 0.5f;
            float barY = boxY - gap - barThickness;
            drawHealthBar(player, barX, barY, barW, barThickness, Math.min(barThickness * 0.5f, 3.0f));
            top = barY;
        }

        if (showTotemPops.isEnabled()) {
            int pops = TotemPopManager.getInstance().getPopCount(player);
            if (pops > 0) {
                String badge = pops < POP_LABELS.length ? POP_LABELS[pops] : "x" + pops;
                float bSize = size * 0.85f;
                float bw = font.getWidth(badge, bSize) + size * 0.5f;
                float bh = font.getTextHeight(bSize) + size * 0.2f;
                float bx = boxX + boxW + size * 0.2f;
                float by = boxY + (boxH - bh) * 0.5f;
                int c = totemBadgeColor.getValue().getRGB();
                Msdf.rect(bx, by, bw, bh, Math.min(bh * 0.5f, (float) rounding.getValue() + 2.0f), 0xE0000000 | (c & 0x00FFFFFF));
                Msdf.text(font, badge, bx + size * 0.25f, by + size * 0.1f, bSize, 0xFF101010);
            }
        }

        if (showHeldItem.isEnabled() && itemCount < MAX_ITEM_ICONS) {
            ItemStack held = player.getMainHandStack();
            if (held != null && !held.isEmpty()) {
                itemStacks[itemCount] = held;
                itemX[itemCount] = cx;
                itemY[itemCount] = top - size * 0.15f;
                itemSizes[itemCount] = size * 1.6f;
                itemCount++;
            }
        }

        if (background.isEnabled()) {
            int alpha = (int) MathHelper.clamp(bgOpacity.getValue() * 255.0, 0.0, 255.0);
            int bg = (alpha << 24) | (bgColor.getValue().getRGB() & 0x00FFFFFF);
            Msdf.rect(boxX, boxY, boxW, totalH, (float) rounding.getValue(), bg);
        }

        if (outline.isEnabled()) {
            int oc = outlineColor.getValue().getRGB();
            float t = (float) outlineThickness.getValue();
            Msdf.rect(boxX, boxY, boxW, t, 0.0f, oc);                     // top
            Msdf.rect(boxX, boxY + totalH - t, boxW, t, 0.0f, oc);        // bottom
            Msdf.rect(boxX, boxY, t, totalH, 0.0f, oc);                   // left
            Msdf.rect(boxX + boxW - t, boxY, t, totalH, 0.0f, oc);        // right
        }

        if (healthBar.isEnabled() && !barAbove) {
            drawHealthBar(player, boxX, boxY + boxH, boxW, healthBarH, 0.0f);
        }

        int argb = 0xFF000000 | (nameColor().getRGB() & 0x00FFFFFF);
        float textX = boxX + padX;
        float textY = boxY + padY;
        if (textShadow.isEnabled()) {
            Msdf.textWithShadow(font, label, textX, textY, size, argb);
        } else {
            Msdf.text(font, label, textX, textY, size, argb);
        }
    }

    private static final String[] POP_LABELS = {"x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x9", "x10",
            "x11", "x12", "x13", "x14", "x15", "x16", "x17", "x18", "x19", "x20"};
    private static final int ABSORPTION_ARGB = 0xFFFFC832;

    /** Track + health fill (+ gold absorption segment after it), health measured against max health. */
    private void drawHealthBar(PlayerEntity player, float x, float y, float w, float h, float radius) {
        float max = Math.max(1.0f, player.getMaxHealth());
        float absorption = absorptionBar.isEnabled() ? player.getAbsorptionAmount() : 0.0f;
        float hp = MathHelper.clamp(player.getHealth() / max, 0.0f, 1.0f);
        float abs = MathHelper.clamp(absorption / max, 0.0f, 1.0f - hp);
        if (!absorptionBar.isEnabled()) {
            // Legacy behaviour: absorption folded into the single fill.
            hp = MathHelper.clamp((player.getHealth() + player.getAbsorptionAmount()) / max, 0.0f, 1.0f);
        }
        Msdf.rect(x, y, w, h, radius, healthBarTrackColor.getValue().getRGB());
        if (hp > 0.0f) Msdf.rect(x, y, w * hp, h, radius, healthColor(hp));
        if (abs > 0.0f) Msdf.rect(x + w * hp, y, w * abs, h, radius, ABSORPTION_ARGB);
    }

    private Color nameColor() {
        return themeAccent.isEnabled() ? AccentState.accentColor() : textColor.getValue();
    }

    /**
     * Health bar fill colour. With Custom Health Colors on, blends the user's empty -> full colours
     * by the health fraction; otherwise the usual red-at-0 to green-at-full hue sweep.
     */
    private int healthColor(float pct) {
        if (customHealthColors.isEnabled()) {
            Color lo = healthEmptyColor.getValue();
            Color hi = healthFullColor.getValue();
            int r = (int) (lo.getRed() + (hi.getRed() - lo.getRed()) * pct);
            int g = (int) (lo.getGreen() + (hi.getGreen() - lo.getGreen()) * pct);
            int b = (int) (lo.getBlue() + (hi.getBlue() - lo.getBlue()) * pct);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return 0xFF000000 | (Color.HSBtoRGB(pct / 3.0f, 0.85f, 1.0f) & 0x00FFFFFF);
    }
}
