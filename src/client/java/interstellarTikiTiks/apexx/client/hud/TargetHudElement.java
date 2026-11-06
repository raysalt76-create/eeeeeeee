package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiRenderer;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.awt.Color;
import java.util.List;
import java.util.Locale;

/** GPU-native opponent pill, updated only by the normal client attack callback. */
public final class TargetHudElement extends HUDElement {
    private static final float CARD_WIDTH = 228.0f;
    private static final float CARD_HEIGHT = 74.0f;
    private static final float AVATAR_SIZE = 46.0f;

    private final ModeSetting crosshairSide = new ModeSetting("Crosshair Side", "Right", "Left", "Above", "Below")
            .setDescription("Choose which side of the crosshair shows the opponent pill.");
    private final SliderSetting crosshairGap = new SliderSetting("Crosshair Gap", 18.0, 8.0, 64.0, 0)
            .setDescription("Space between the crosshair and the pill.");
    private final SliderSetting visibleDuration = new SliderSetting("Stay Visible", 2.8, 0.5, 8.0, 1)
            .setDescription("Seconds the opponent pill stays visible after a hit.");
    private final SliderSetting backgroundOpacity = new SliderSetting("Background Opacity", 0.94, 0.65, 1.0, 2)
            .setDescription("Opacity of the glass-dark pill background.");
    private final ModeSetting style = new ModeSetting("Style", "Classic", "Classic", "Compact", "Detailed", "Minimal")
            .setDescription("Classic: the original card. Compact: small card with head, health, armor and distance. "
                    + "Detailed: large card with exact health and absorption, per-piece armor durability, distance "
                    + "and ping. Minimal: one slim line with a health strip.");

    private static PlayerEntity lastTarget;
    private static long lastTargetTime;

    private PlayerEntity animatedTarget;
    private float animatedHealth = 1.0f;
    private float animatedAbsorption;
    private float previousHealth = 1.0f;
    private long targetShownAt;
    private long damagePulseAt;

    public TargetHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
        setWidth(CARD_WIDTH);
        setHeight(CARD_HEIGHT);
    }

    /** Called from the existing accepted local attack callback. Hovering a player does not activate the pill. */
    public static void updateTarget(LivingEntity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (entity instanceof PlayerEntity target && target.isAlive() && target != client.player) {
            lastTarget = target;
            lastTargetTime = System.currentTimeMillis();
        }
    }

    public static LivingEntity getLastTarget() {
        return lastTarget;
    }

    public static void clearTarget() {
        lastTarget = null;
        lastTargetTime = 0L;
    }

    public float getEditorX(int screenWidth) {
        return anchorX(screenWidth, (float) (getWidth() * getScale())) + (float) getX();
    }

    public float getEditorY(int screenHeight) {
        return anchorY(screenHeight, (float) (getHeight() * getScale())) + (float) getY();
    }

    /** Logical unscaled dimensions for the HUD editor's hitbox and alignment guides. */
    public float getWidgetWidth() {
        return switch (style.getCurrentMode()) {
            case "Compact" -> COMPACT_WIDTH;
            case "Detailed" -> DETAILED_WIDTH;
            case "Minimal" -> MINIMAL_WIDTH;
            default -> CARD_WIDTH;
        };
    }

    public float getWidgetHeight() {
        return switch (style.getCurrentMode()) {
            case "Compact" -> COMPACT_HEIGHT;
            case "Detailed" -> DETAILED_HEIGHT;
            case "Minimal" -> MINIMAL_HEIGHT;
            default -> CARD_HEIGHT;
        };
    }

    private static final float COMPACT_WIDTH = 176.0f;
    private static final float COMPACT_HEIGHT = 48.0f;
    private static final float DETAILED_WIDTH = 240.0f;
    private static final float DETAILED_HEIGHT = 96.0f;
    private static final float MINIMAL_WIDTH = 196.0f;
    private static final float MINIMAL_HEIGHT = 26.0f;
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final String[] ARMOR_LABELS = {"H", "C", "L", "B"};

    @Override
    protected List<Setting> elementSettings() {
        return List.of(style, crosshairSide, crosshairGap, visibleDuration, backgroundOpacity);
    }

    /**
     * Add the target pill to the shared GPU UI display list. Coordinates are absolute GUI pixels;
     * x/y stored on this element remain draggable offsets from its selected crosshair-side anchor.
     */
    public void appendGpu(UiDisplayList.Builder builder, int screenWidth, int screenHeight,
                          float delta, boolean editorPreview) {
        setWidth(getWidgetWidth());
        setHeight(getWidgetHeight());
        MinecraftClient client = MinecraftClient.getInstance();

        PlayerEntity target;
        String name;
        float healthRatio;
        float absorptionRatio;
        float opacity;
        if (editorPreview) {
            target = client.player;
            name = "Opponent";
            healthRatio = 0.72f;
            absorptionRatio = 0.0f;
            opacity = isEnabled() ? 0.86f : 0.38f;
        } else {
            if (client.player == null || client.world == null) {
                lastTarget = null;
                animatedTarget = null;
                return;
            }
            target = lastTarget;
            if (target == null) return;

            long now = System.currentTimeMillis();
            long elapsed = now - lastTargetTime;
            long visibleMillis = Math.max(500L, Math.round(visibleDuration.getValue() * 1000.0));
            if (elapsed > visibleMillis + 180L) return;
            float maxHealth = Math.max(1.0f, target.getMaxHealth());
            float absorption = Math.max(0.0f, target.getAbsorptionAmount());
            float totalHealth = maxHealth + absorption;
            healthRatio = MathHelper.clamp(target.getHealth() / totalHealth, 0.0f, 1.0f);
            absorptionRatio = MathHelper.clamp(absorption / totalHealth, 0.0f, 1.0f - healthRatio);
            name = target.getName().getString();

            if (target != animatedTarget) {
                animatedTarget = target;
                targetShownAt = now;
                animatedHealth = healthRatio;
                animatedAbsorption = absorptionRatio;
                previousHealth = healthRatio;
            }
            if (healthRatio < previousHealth - 0.004f) damagePulseAt = now;
            previousHealth = healthRatio;
            float smoothing = 1.0f - (float) Math.exp(-frameSeconds() * 13.0f);
            animatedHealth = MathHelper.lerp(MathHelper.clamp(smoothing, 0.0f, 1.0f), animatedHealth, healthRatio);
            animatedAbsorption = MathHelper.lerp(MathHelper.clamp(smoothing, 0.0f, 1.0f), animatedAbsorption,
                    absorptionRatio);
            opacity = elapsed > visibleMillis
                    ? MathHelper.clamp(1.0f - (elapsed - visibleMillis) / 180.0f, 0.0f, 1.0f)
                    : MathHelper.clamp((now - targetShownAt) / 180.0f, 0.0f, 1.0f);
        }

        if (target == null && !editorPreview) return;
        float scale = (float) getScale();
        float width = (float) getWidth() * scale;
        float height = (float) getHeight() * scale;
        float x = getEditorX(screenWidth);
        float y = getEditorY(screenHeight);
        float alpha = opacity * (editorPreview && !isEnabled() ? 0.55f : 1.0f);
        float radius = 8.0f * scale;

        String mode = style.getCurrentMode();
        if ("Compact".equals(mode) || "Detailed".equals(mode) || "Minimal".equals(mode)) {
            appendStyled(builder, client, mode, target, name, x, y, width, height, scale, alpha,
                    editorPreview ? healthRatio : animatedHealth,
                    editorPreview ? absorptionRatio : animatedAbsorption, editorPreview);
            return;
        }

        int textPrimary = alpha(HudTheme.text(), alpha);
        int textSecondary = alpha(HudTheme.muted(), alpha * 0.85f);

        float px = x + 15.0f * scale;
        float py = y + (height - AVATAR_SIZE * scale) * 0.5f;
        float infoX = x + 73.0f * scale;
        float barX = infoX;
        float barY = y + 52.0f * scale;
        float barWidth = width - 88.0f * scale;
        float barHeight = 6.0f * scale;

        if (editorPreview && !isEnabled()) {
            GpuHudRenderer.untoggledPane(builder, x, y, width, height, radius);
        } else {
            GpuHudRenderer.glassPane(builder, x, y, width, height, radius, alpha,
                    (float) this.backgroundOpacity.getValue());
        }

        Identifier skin = skinTexture(client, target);
        builder.circle(px + AVATAR_SIZE * scale * 0.5f, py + AVATAR_SIZE * scale * 0.5f,
                AVATAR_SIZE * scale * 0.5f, alpha(0x80000000, alpha));
        if (skin != null) {
            float size = AVATAR_SIZE * scale;
            builder.image(px, py, size, size, size * 0.5f, alpha(0xffffffff, alpha), skin,
                    8.0f / 64.0f, 8.0f / 64.0f, 16.0f / 64.0f, 16.0f / 64.0f);
            builder.image(px, py, size, size, size * 0.5f, alpha(0xffffffff, alpha), skin,
                    40.0f / 64.0f, 8.0f / 64.0f, 48.0f / 64.0f, 16.0f / 64.0f);
        } else {
            String initial = (name != null && !name.isEmpty()) ? name.substring(0, 1).toUpperCase(Locale.ROOT) : "A";
            float initialWidth = GpuUiRenderer.textWidth(initial, 13.0f * scale);
            builder.text(px + (AVATAR_SIZE * scale - initialWidth) * 0.5f, py + 17.0f * scale, 13.0f * scale,
                    textPrimary, initial, UiDrawCommand.TextWeight.SEMIBOLD);
        }
        builder.border(px, py, AVATAR_SIZE * scale, AVATAR_SIZE * scale,
                AVATAR_SIZE * scale * 0.5f, 1.0f * scale, alpha(HudTheme.rim(0x35), alpha));
        int statusColor = (target != null && target.isAlive()) ? 0xFF65DCA8 : 0xFFFF7772;
        builder.circle(px + AVATAR_SIZE * scale - 2.5f * scale,
                py + AVATAR_SIZE * scale - 2.5f * scale, 5.0f * scale, alpha(statusColor, alpha));
        builder.circle(px + AVATAR_SIZE * scale - 2.5f * scale,
                py + AVATAR_SIZE * scale - 2.5f * scale, 2.4f * scale, alpha(0xFFFFFFFF, alpha));

        String displayName = fitName(name, 129.0f * scale, 13.0f * scale);
        builder.text(infoX, y + 16.0f * scale, 13.0f * scale, textPrimary,
                displayName, UiDrawCommand.TextWeight.SEMIBOLD);
        String label = editorPreview ? "HUD PREVIEW" : "OPPONENT";
        builder.text(infoX, y + 33.0f * scale, 7.5f * scale, textSecondary, label,
                UiDrawCommand.TextWeight.SEMIBOLD);

        builder.pill(barX, barY, barWidth, barHeight, alpha(0x60000000, alpha));
        builder.border(barX, barY, barWidth, barHeight, barHeight * 0.5f, 0.7f * scale, alpha(HudTheme.rim(0x28), alpha));
        float shownHealth = editorPreview ? healthRatio : animatedHealth;
        float shownAbsorption = editorPreview ? absorptionRatio : animatedAbsorption;
        float healthWidth = barWidth * shownHealth;
        if (healthWidth > 0.5f * scale) {
            Color healthColor = healthTint(shownHealth);
            int healthColorA = alpha(healthColor.getRGB(), alpha);
            int healthColorB = alpha(healthColor.brighter().getRGB(), alpha);
            builder.gradient(barX, barY, healthWidth, barHeight, barHeight * 0.5f,
                    healthColorB, healthColorA, healthColorA, healthColorB);
        }
        if (shownAbsorption > 0.005f) {
            float absorptionX = barX + barWidth * shownHealth;
            float absorptionWidth = Math.min(barWidth * shownAbsorption, barWidth - (absorptionX - barX));
            builder.pill(absorptionX, barY, absorptionWidth, barHeight, alpha(0xffffd477, alpha * 0.9f));
        }

        long now = System.currentTimeMillis();
        float pulse = MathHelper.clamp(1.0f - (now - damagePulseAt) / 210.0f, 0.0f, 1.0f);
        if (!editorPreview && pulse > 0.0f) {
            builder.border(x, y, width, height, radius, 1.1f * scale,
                    alpha(0xffff8b99, pulse * 0.7f * alpha));
        }
    }

    /** Compact / Detailed / Minimal cards: skin head, health + absorption, armor and distance. */
    private void appendStyled(UiDisplayList.Builder b, MinecraftClient client, String mode, PlayerEntity target,
                              String name, float x, float y, float w, float h, float s, float alpha,
                              float healthShown, float absorptionShown, boolean preview) {
        float hp, maxHp, absorption, distance;
        int armorPoints, ping;
        if (preview || target == null) {
            hp = 14.5f;
            maxHp = 20f;
            absorption = 4f;
            armorPoints = 20;
            distance = 3.4f;
            ping = 42;
        } else {
            hp = Math.max(0f, target.getHealth());
            maxHp = Math.max(1f, target.getMaxHealth());
            absorption = Math.max(0f, target.getAbsorptionAmount());
            armorPoints = Math.max(0, target.getArmor());
            distance = client.player == null ? 0f : target.distanceTo(client.player);
            ping = pingOf(client, target);
        }
        float[] durability = armorDurability(preview ? null : target, preview);
        float radius = ("Minimal".equals(mode) ? 6f : 8f) * s;
        if (preview && !isEnabled()) {
            GpuHudRenderer.untoggledPane(b, x, y, w, h, radius);
        } else {
            GpuHudRenderer.glassPane(b, x, y, w, h, radius, alpha, (float) this.backgroundOpacity.getValue());
        }
        int text = alpha(HudTheme.text(), alpha);
        int muted = alpha(HudTheme.muted(), alpha * .85f);
        int hpColour = alpha(healthTint(hp / maxHp).getRGB(), alpha);
        int gold = alpha(0xFFFFD477, alpha);
        String hpText = String.format(Locale.ROOT, "%.1f", hp);
        String absText = absorption > 0.05f ? String.format(Locale.ROOT, "+%.1f", absorption) : "";
        String distText = String.format(Locale.ROOT, "%.1fm", distance);

        switch (mode) {
            case "Compact" -> {
                float head = 32f * s;
                head(b, client, target, name, x + 8f * s, y + 8f * s, head, 6f * s, alpha);
                float infoX = x + 48f * s;
                float distW = GpuUiRenderer.textWidth(distText, 8f * s);
                b.text(x + w - 8f * s - distW, y + 9.5f * s, 8f * s, muted, distText, UiDrawCommand.TextWeight.SEMIBOLD);
                b.text(infoX, y + 8f * s, 10.5f * s, text, fitName(name, w - 48f * s - 16f * s - distW, 10.5f * s),
                        UiDrawCommand.TextWeight.SEMIBOLD);
                healthBar(b, infoX, y + 23f * s, w - 56f * s, 5f * s, healthShown, absorptionShown, alpha, s);
                float ly = y + 32.5f * s;
                b.text(infoX, ly, 7.5f * s, hpColour, hpText, UiDrawCommand.TextWeight.SEMIBOLD);
                if (!absText.isEmpty()) {
                    b.text(infoX + GpuUiRenderer.textWidth(hpText, 7.5f * s) + 3f * s, ly, 7.5f * s, gold, absText,
                            UiDrawCommand.TextWeight.SEMIBOLD);
                }
                String armor = Integer.toString(armorPoints);
                float ax = x + w - 8f * s - GpuUiRenderer.textWidth(armor, 7.5f * s);
                b.text(ax, ly, 7.5f * s, text, armor, UiDrawCommand.TextWeight.SEMIBOLD);
                float px = ax - 4f * s;
                for (int i = ARMOR_SLOTS.length - 1; i >= 0; i--) {
                    px -= 5f * s;
                    b.rect(px, ly + 1.5f * s, 4f * s, 4f * s, 1f * s, alpha(durabilityColour(durability[i]), alpha));
                    px -= 1.5f * s;
                }
            }
            case "Detailed" -> {
                float head = 56f * s;
                float hx = x + 10f * s, hy = y + 10f * s;
                head(b, client, target, name, hx, hy, head, 8f * s, alpha);
                String pingText = ping < 0 ? "--" : ping + "ms";
                b.text(hx + (head - GpuUiRenderer.textWidth(pingText, 7f * s)) * .5f, hy + head + 6f * s, 7f * s,
                        muted, pingText, UiDrawCommand.TextWeight.SEMIBOLD);
                String armorText = "ARMOR " + armorPoints;
                b.text(hx + (head - GpuUiRenderer.textWidth(armorText, 6.5f * s)) * .5f, hy + head + 15f * s,
                        6.5f * s, muted, armorText, UiDrawCommand.TextWeight.SEMIBOLD);
                float infoX = x + 76f * s, infoW = w - 86f * s;
                float distW = GpuUiRenderer.textWidth(distText, 8.5f * s);
                b.text(x + w - 10f * s - distW, y + 12f * s, 8.5f * s, muted, distText, UiDrawCommand.TextWeight.SEMIBOLD);
                b.text(infoX, y + 10f * s, 13f * s, text, fitName(name, infoW - distW - 8f * s, 13f * s),
                        UiDrawCommand.TextWeight.SEMIBOLD);
                String hpLine = hpText + " / " + String.format(Locale.ROOT, "%.0f", maxHp);
                b.text(infoX, y + 28f * s, 8.5f * s, hpColour, hpLine, UiDrawCommand.TextWeight.SEMIBOLD);
                if (!absText.isEmpty()) {
                    b.text(infoX + GpuUiRenderer.textWidth(hpLine, 8.5f * s) + 4f * s, y + 28f * s, 8.5f * s, gold,
                            absText, UiDrawCommand.TextWeight.SEMIBOLD);
                }
                healthBar(b, infoX, y + 42f * s, infoW, 6f * s, healthShown, absorptionShown, alpha, s);
                float cellGap = 4f * s;
                float cellW = (infoW - cellGap * 3f) / 4f, cellH = 28f * s, cy = y + 56f * s;
                for (int i = 0; i < ARMOR_SLOTS.length; i++) {
                    float cx = infoX + i * (cellW + cellGap);
                    b.rect(cx, cy, cellW, cellH, 3f * s, alpha(0x70000000, alpha));
                    b.border(cx, cy, cellW, cellH, 3f * s, .7f * s, alpha(HudTheme.rim(0x28), alpha));
                    b.text(cx + 4f * s, cy + 4f * s, 7f * s, muted, ARMOR_LABELS[i], UiDrawCommand.TextWeight.SEMIBOLD);
                    float d = durability[i];
                    String pct = d < 0f ? "-" : Math.round(d * 100f) + "%";
                    b.text(cx + cellW - 4f * s - GpuUiRenderer.textWidth(pct, 7f * s), cy + 4f * s, 7f * s,
                            d < 0f ? muted : alpha(durabilityColour(d), alpha), pct, UiDrawCommand.TextWeight.SEMIBOLD);
                    float bw = cellW - 8f * s;
                    b.rect(cx + 4f * s, cy + cellH - 8f * s, bw, 2.5f * s, 1.25f * s, alpha(HudTheme.rim(0x40), alpha));
                    if (d > 0f) {
                        b.rect(cx + 4f * s, cy + cellH - 8f * s, Math.max(1f, bw * d), 2.5f * s, 1.25f * s,
                                alpha(durabilityColour(d), alpha));
                    }
                }
            }
            default -> { // Minimal
                float head = 18f * s;
                head(b, client, target, name, x + 4f * s, y + 4f * s, head, 4f * s, alpha);
                float size = 8.5f * s, ty = y + (h - size) * .5f - .5f * s;
                String armor = "A" + armorPoints;
                float right = x + w - 8f * s;
                float distW = GpuUiRenderer.textWidth(distText, size);
                b.text(right - distW, ty, size, muted, distText, UiDrawCommand.TextWeight.SEMIBOLD);
                right -= distW + 7f * s;
                float armorW = GpuUiRenderer.textWidth(armor, size);
                b.text(right - armorW, ty, size, text, armor, UiDrawCommand.TextWeight.SEMIBOLD);
                right -= armorW + 7f * s;
                if (!absText.isEmpty()) {
                    float absW = GpuUiRenderer.textWidth(absText, size);
                    b.text(right - absW, ty, size, gold, absText, UiDrawCommand.TextWeight.SEMIBOLD);
                    right -= absW + 3f * s;
                }
                float hpW = GpuUiRenderer.textWidth(hpText, size);
                b.text(right - hpW, ty, size, hpColour, hpText, UiDrawCommand.TextWeight.SEMIBOLD);
                right -= hpW;
                float nameX = x + 28f * s;
                b.text(nameX, ty, size + .5f * s, text, fitName(name, Math.max(10f * s, right - nameX - 8f * s), size + .5f * s),
                        UiDrawCommand.TextWeight.SEMIBOLD);
                healthBar(b, x + radius, y + h - 3f * s, w - radius * 2f, 2f * s, healthShown, absorptionShown, alpha, s);
            }
        }

        long now = System.currentTimeMillis();
        float pulse = MathHelper.clamp(1.0f - (now - damagePulseAt) / 210.0f, 0.0f, 1.0f);
        if (!preview && pulse > 0.0f) {
            b.border(x, y, w, h, radius, 1.1f * s, alpha(0xffff8b99, pulse * 0.7f * alpha));
        }
    }

    private static void head(UiDisplayList.Builder b, MinecraftClient client, PlayerEntity target, String name,
                             float x, float y, float size, float radius, float alpha) {
        b.rect(x, y, size, size, radius, alpha(0x80000000, alpha));
        Identifier skin = skinTexture(client, target);
        if (skin != null) {
            b.image(x, y, size, size, radius, alpha(0xffffffff, alpha), skin,
                    8.0f / 64.0f, 8.0f / 64.0f, 16.0f / 64.0f, 16.0f / 64.0f);
            b.image(x, y, size, size, radius, alpha(0xffffffff, alpha), skin,
                    40.0f / 64.0f, 8.0f / 64.0f, 48.0f / 64.0f, 16.0f / 64.0f);
        } else {
            String initial = (name != null && !name.isEmpty()) ? name.substring(0, 1).toUpperCase(Locale.ROOT) : "A";
            float font = size * .42f;
            b.text(x + (size - GpuUiRenderer.textWidth(initial, font)) * .5f, y + (size - font) * .5f, font,
                    alpha(HudTheme.text(), alpha), initial, UiDrawCommand.TextWeight.SEMIBOLD);
        }
        b.border(x, y, size, size, radius, .8f, alpha(HudTheme.rim(0x35), alpha));
    }

    private static void healthBar(UiDisplayList.Builder b, float x, float y, float w, float h, float health,
                                  float absorption, float alpha, float s) {
        if (w <= 0f || h <= 0f) return;
        b.pill(x, y, w, h, alpha(0x60000000, alpha));
        float hw = w * MathHelper.clamp(health, 0f, 1f);
        if (hw > .5f * s) {
            Color c = healthTint(health);
            b.gradient(x, y, hw, h, h * .5f, alpha(c.brighter().getRGB(), alpha), alpha(c.getRGB(), alpha),
                    alpha(c.getRGB(), alpha), alpha(c.brighter().getRGB(), alpha));
        }
        if (absorption > .005f) {
            float aw = Math.min(w * absorption, w - hw);
            if (aw > .5f) b.pill(x + hw, y, aw, h, alpha(0xffffd477, alpha * .9f));
        }
    }

    /** Remaining durability 0..1 per armor slot (1 for unbreakable pieces), -1 for an empty slot. */
    private static float[] armorDurability(PlayerEntity target, boolean preview) {
        float[] out = new float[ARMOR_SLOTS.length];
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            if (preview) {
                out[i] = new float[]{.92f, .64f, .35f, .12f}[i];
                continue;
            }
            ItemStack stack = target == null ? ItemStack.EMPTY : target.getEquippedStack(ARMOR_SLOTS[i]);
            if (stack == null || stack.isEmpty()) out[i] = -1f;
            else if (!stack.isDamageable() || stack.getMaxDamage() <= 0) out[i] = 1f;
            else out[i] = MathHelper.clamp(1f - stack.getDamage() / (float) stack.getMaxDamage(), 0f, 1f);
        }
        return out;
    }

    private static int durabilityColour(float ratio) {
        if (ratio < 0f) return 0x40FFFFFF;
        return ratio > .5f ? 0xFF65DCA8 : ratio > .2f ? 0xFFFFC45C : 0xFFFF7772;
    }

    private static int pingOf(MinecraftClient client, PlayerEntity target) {
        if (client.getNetworkHandler() == null || target == null) return -1;
        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(target.getUuid());
        return entry == null ? -1 : Math.max(0, entry.getLatency());
    }

    /** Layout-unit screen size: the editor, mouse and renderer all place this widget in these units. */
    private static int[] layoutSize() {
        MinecraftClient client = MinecraftClient.getInstance();
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        float scale = GpuHudRenderer.viewportScale(sw, sh);
        return new int[] {Math.round(sw / scale), Math.round(sh / scale)};
    }

    @Override
    public void startDragging(double mouseX, double mouseY) {
        int[] layout = layoutSize();
        super.startDragging(mouseX - anchorX(layout[0], (float) (getWidth() * getScale())),
                mouseY - anchorY(layout[1], (float) (getHeight() * getScale())));
    }

    @Override
    public void mouseDragged(double mouseX, double mouseY) {
        int[] layout = layoutSize();
        super.mouseDragged(mouseX - anchorX(layout[0], (float) (getWidth() * getScale())),
                mouseY - anchorY(layout[1], (float) (getHeight() * getScale())));
    }

    @Override
    public void render(DrawContext context, float delta) {
        MinecraftClient client = MinecraftClient.getInstance();
        UiDisplayList.Builder builder = UiDisplayList.builder();
        int[] layout = layoutSize();
        appendGpu(builder, layout[0], layout[1],
                delta, client.currentScreen != null);
        GpuUiRenderer.render(context, builder.build());
    }

    private float anchorX(int screenWidth, float scaledWidth) {
        float centerX = screenWidth * 0.5f;
        float gap = (float) crosshairGap.getValue();
        return switch (crosshairSide.getCurrentMode()) {
            case "Left" -> centerX - gap - scaledWidth;
            case "Above", "Below" -> centerX - scaledWidth * 0.5f;
            default -> centerX + gap;
        };
    }

    private float anchorY(int screenHeight, float scaledHeight) {
        float centerY = screenHeight * 0.5f;
        float gap = (float) crosshairGap.getValue();
        return switch (crosshairSide.getCurrentMode()) {
            case "Above" -> centerY - gap - scaledHeight;
            case "Below" -> centerY + gap;
            default -> centerY - scaledHeight * 0.5f;
        };
    }

    private static Identifier skinTexture(MinecraftClient client, Object candidate) {
        if (!(candidate instanceof PlayerEntity target) || client.getNetworkHandler() == null) return null;
        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(target.getUuid());
        return entry == null ? null : entry.getSkinTextures().body().texturePath();
    }

    private static String fitName(String name, float maxWidth, float size) {
        if (GpuUiRenderer.textWidth(name, size) <= maxWidth) return name;
        String suffix = "…";
        int end = name.length();
        while (end > 0 && GpuUiRenderer.textWidth(name.substring(0, end) + suffix, size) > maxWidth) {
            end = name.offsetByCodePoints(0, Math.max(0, name.codePointCount(0, end) - 1));
        }
        return end == 0 ? suffix : name.substring(0, end) + suffix;
    }

    private static Color healthTint(float ratio) {
        float r = MathHelper.clamp(ratio, 0.0f, 1.0f);
        if (r > 0.5f) {
            float t = (r - 0.5f) * 2.0f;
            int red = Math.round(MathHelper.lerp(t, 255f, 101f));
            int green = Math.round(MathHelper.lerp(t, 196f, 220f));
            int blue = Math.round(MathHelper.lerp(t, 92f, 168f));
            return new Color(red, green, blue);
        } else {
            float t = r * 2.0f;
            int red = Math.round(MathHelper.lerp(t, 255f, 255f));
            int green = Math.round(MathHelper.lerp(t, 119f, 196f));
            int blue = Math.round(MathHelper.lerp(t, 114f, 92f));
            return new Color(red, green, blue);
        }
    }

    private static int alpha(int color, float multiplier) {
        int original = (color >>> 24) & 0xff;
        int scaled = MathHelper.clamp(Math.round(original * multiplier), 0, 255);
        return (color & 0x00ffffff) | (scaled << 24);
    }
}
