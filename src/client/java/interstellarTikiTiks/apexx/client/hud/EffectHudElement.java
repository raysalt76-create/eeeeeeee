package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.hud.HUDElement;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;

import interstellarTikiTiks.apexx.client.util.GuiShaderStyle;
import interstellarTikiTiks.apexx.client.util.Renderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class EffectHudElement extends HUDElement {

    private final BooleanSetting showIcon = new BooleanSetting("Show Icon", true);
    private final BooleanSetting showDuration = new BooleanSetting("Duration", true);
    private final BooleanSetting showAmplifier = new BooleanSetting("Show Amplifier", true);
    private final BooleanSetting showColorMark = new BooleanSetting("Colour Mark", true);
    private final BooleanSetting showHeader = new BooleanSetting("Header", false);
    private final ModeSetting timeFormat = new ModeSetting("Time Format", "Minutes:Seconds", "Minutes:Seconds",
            "Seconds Only", "Both");
    private final BooleanSetting showProgressBar = new BooleanSetting("Show Progress Bar", true);
    private final BooleanSetting sortByDuration = new BooleanSetting("Sort by Duration", true);
    private final BooleanSetting sortByName = new BooleanSetting("Sort by Name", false);

    private final SliderSetting bgOpacity = new SliderSetting("BG Opacity", 0.56, 0.0, 1.0, 2);
    private final SliderSetting cornerRadius = new SliderSetting("Corner Radius", 8.0, 0.0, 20.0, 1);
    private final SliderSetting spacing = new SliderSetting("Spacing", 4.0, 0.0, 15.0, 1);
    private final SliderSetting iconSize = new SliderSetting("Icon Size", 18.0, 12.0, 32.0, 0);

    private final BooleanSetting glowEffect = new BooleanSetting("Glow Effect", true);
    private final SliderSetting glowSize = new SliderSetting("Glow Size", 8.0, 0.0, 20.0, 1);
    private final SliderSetting glowIntensity = new SliderSetting("Glow Intensity", 0.5, 0.0, 1.0, 2);
    private final BooleanSetting slideAnimation = new BooleanSetting("Slide Animation", true);
    private final BooleanSetting fadeAnimation = new BooleanSetting("Fade Animation", true);
    private final BooleanSetting pulseOnLowTime = new BooleanSetting("Pulse on Low Time", true);
    private final SliderSetting lowTimeThreshold = new SliderSetting("Low Time Threshold", 10.0, 5.0, 30.0, 0);

    private final SliderSetting progressBarHeight = new SliderSetting("Progress Bar Height", 3.0, 1.0, 8.0, 0);
    private final BooleanSetting coloredProgressBar = new BooleanSetting("Colored Progress Bar", true);

    private final List<EffectEntry> effectEntries = new ArrayList<>();
    private long lastUpdateTime = System.currentTimeMillis();

    public EffectHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
    }

    protected List<Setting> elementSettings() {
        List<Setting> settings = new ArrayList<>();

        settings.add(showIcon);
        settings.add(showDuration);
        settings.add(showAmplifier);
        settings.add(showColorMark);
        settings.add(showHeader);
        settings.add(timeFormat);
        settings.add(showProgressBar);
        settings.add(sortByDuration);
        settings.add(sortByName);

        settings.add(bgOpacity);
        settings.add(cornerRadius);
        settings.add(spacing);
        settings.add(iconSize);

        settings.add(glowEffect);
        settings.add(glowSize);
        settings.add(glowIntensity);
        settings.add(slideAnimation);
        settings.add(fadeAnimation);
        settings.add(pulseOnLowTime);
        settings.add(lowTimeThreshold);

        settings.add(progressBarHeight);
        settings.add(coloredProgressBar);
        return settings;
    }

    @Override
    public void render(DrawContext context, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.currentScreen != null) {
            renderPlaceholder(context, mc);
            return;
        }

        if (mc.player == null) {
            setWidth(0);
            setHeight(0);
            return;
        }
        Collection<StatusEffectInstance> activeEffects = mc.player.getStatusEffects();
        if (activeEffects.isEmpty() && effectEntries.isEmpty()) {
            setWidth(0);
            setHeight(0);
            effectEntries.clear();
            return;
        }

        updateEffectEntries(activeEffects, delta);
        renderEffects(context, mc, delta);
    }

    private void updateEffectEntries(Collection<StatusEffectInstance> activeEffects, float delta) {
        long currentTime = System.currentTimeMillis();
        float deltaTime = (currentTime - lastUpdateTime) / 1000.0f;
        deltaTime = Math.min(deltaTime, 0.1f);
        lastUpdateTime = currentTime;

        List<StatusEffectInstance> sortedEffects = new ArrayList<>(activeEffects);
        if (sortByName.isEnabled()) {
            sortedEffects.sort((a, b) -> cleanName(a.getEffectType().value().getName().getString())
                    .compareToIgnoreCase(cleanName(b.getEffectType().value().getName().getString())));
        } else if (sortByDuration.isEnabled()) {
            sortedEffects.sort((a, b) -> Integer.compare(a.getDuration(), b.getDuration()));
        }

        List<EffectEntry> newEntries = new ArrayList<>();
        for (StatusEffectInstance effect : sortedEffects) {
            EffectEntry existing = findEntry(effect);
            if (existing != null) {
                existing.fadeOut = false;
                existing.effect = effect;
                existing.update(deltaTime);
                newEntries.add(existing);
            } else {
                EffectEntry newEntry = new EffectEntry(effect);
                newEntry.update(deltaTime);
                newEntries.add(newEntry);
            }
        }

        for (EffectEntry entry : effectEntries) {
            if (!newEntries.contains(entry)) {
                entry.fadeOut = true;
            }
        }

        final float finalDeltaTime = deltaTime;
        effectEntries.removeIf(entry -> {
            if (entry.fadeOut) {
                entry.update(finalDeltaTime);
                return entry.fadeAlpha <= 0;
            }
            return false;
        });

        // Entries still fading out stay in the list (after the live ones) so the fade actually plays.
        List<EffectEntry> fading = new ArrayList<>();
        for (EffectEntry entry : effectEntries) {
            if (entry.fadeOut && !newEntries.contains(entry)) fading.add(entry);
        }
        effectEntries.clear();
        effectEntries.addAll(newEntries);
        effectEntries.addAll(fading);
    }

    private EffectEntry findEntry(StatusEffectInstance effect) {
        for (EffectEntry entry : effectEntries) {
            if (entry.effect.getEffectType().equals(effect.getEffectType())) {
                return entry;
            }
        }
        return null;
    }

    private void renderEffects(DrawContext context, MinecraftClient mc, float delta) {
        if (effectEntries.isEmpty()) {
            setWidth(0);
            setHeight(0);
            return;
        }

        final int padding = 8;
        final int iconSizePx = (int) iconSize.getValue();
        final int spacingPx = (int) spacing.getValue();
        final int progressHeight = (int) progressBarHeight.getValue();
        final float fontSize = 12.0f;
        final int gap = 6;
        final int headerHeight = showHeader.isEnabled() ? 15 : 0;

        int maxTextWidth = 0;
        for (EffectEntry entry : effectEntries) {
            String effectName = effectDisplayName(entry.effect);
            String displayText = showDuration.isEnabled()
                    ? effectName + " " + formatTime(entry.effect) : effectName;
            int textWidth = (int) Renderer.get().getTextWidth(displayText, fontSize);
            maxTextWidth = Math.max(maxTextWidth, textWidth);
        }

        int contentWidth = maxTextWidth;
        if (showIcon.isEnabled()) {
            contentWidth += iconSizePx + gap;
        }

        int singleEffectHeight = Math.max(iconSizePx, (int) fontSize + 2);
        if (showProgressBar.isEnabled()) {
            singleEffectHeight += progressHeight + 2;
        }

        int totalWidth = contentWidth + padding * 2;
        int totalHeight = headerHeight + (effectEntries.size() * singleEffectHeight)
                + ((effectEntries.size() - 1) * spacingPx) + padding * 2;

        setWidth(totalWidth);
        setHeight(totalHeight);

        float baseX = (float) getX();
        float baseY = (float) getY();
        Color bgColor = new Color((int) (bgOpacity.getValue() * 255) << 24 | HudTheme.backgroundRgb(), true);
        float radius = (float) cornerRadius.getValue();

        if (glowEffect.isEnabled()) {
            int numGlowLayers = 6;
            for (int i = numGlowLayers; i >= 1; i--) {
                float layerProgress = (float) i / numGlowLayers;
                float layerSize = (float) glowSize.getValue() * layerProgress;
                float glowAlpha = (float) glowIntensity.getValue() * (1.0f - layerProgress) / numGlowLayers;
                Color glowColor = new Color(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(),
                        (int) (glowAlpha * 255));
                Renderer.get().drawRoundedRectStyled(
                        context,
                        baseX - layerSize, baseY - layerSize,
                        totalWidth + layerSize * 2, totalHeight + layerSize * 2,
                        radius + layerSize, glowColor,
                        GuiShaderStyle.GLOW);
            }
        }

        Renderer.get().drawRoundedRectStyled(context, baseX, baseY, totalWidth, totalHeight, radius, bgColor, GuiShaderStyle.HUD);
        Renderer.get().drawRoundedRectOutline(context, baseX, baseY, totalWidth, totalHeight, radius, 1.0f,
                HudTheme.rimColor(70));
        Renderer.get().drawRect(context, baseX, baseY + 4f, 2f, Math.max(1f, totalHeight - 8f), HudTheme.accentColor());

        if (showHeader.isEnabled()) {
            Renderer.get().drawText(context, "ACTIVE EFFECTS", baseX + padding, baseY + 5f,
                    8.0f, HudTheme.mutedColor(), true);
        }

        float currentY = baseY + padding + headerHeight;
        for (EffectEntry entry : effectEntries) {
            renderSingleEffect(context, entry, baseX + padding, currentY, contentWidth, singleEffectHeight,
                    iconSizePx, gap, fontSize, progressHeight, mc);
            if (showColorMark.isEnabled()) {
                int effectColor = entry.effect.getEffectType().value().getColor();
                Renderer.get().drawRect(context, baseX + 3f, currentY + 2f, 2f,
                        Math.max(2f, singleEffectHeight - 4f), new Color(effectColor, true));
            }
            currentY += singleEffectHeight + spacingPx;
        }

        if (showIcon.isEnabled()) {
            float iconY = baseY + padding + headerHeight;

            for (EffectEntry entry : effectEntries) {
                float slideOffset = slideAnimation.isEnabled() ? entry.slideOffset : 0;
                float iconX = baseX + padding + slideOffset;
                float iconYPos = iconY;

                Identifier effectId = Registries.STATUS_EFFECT.getId(entry.effect.getEffectType().value());
                if (effectId != null) {

                    Identifier iconTexture = Identifier.of(effectId.getNamespace(),
                            "textures/mob_effect/" + effectId.getPath() + ".png");
                    context.drawTexture(RenderPipelines.GUI_TEXTURED, iconTexture,
                            (int) iconX, (int) iconYPos,
                            0.0f, 0.0f,
                            iconSizePx, iconSizePx,
                            18, 18,
                            18, 18);
                }

                iconY += singleEffectHeight + spacingPx;
            }
        }
    }

    private void renderSingleEffect(DrawContext context, EffectEntry entry, float x, float y, int width, int height,
            int iconSizePx, int gap, float fontSize, int progressHeight,
            MinecraftClient mc) {
        float slideOffset = slideAnimation.isEnabled() ? entry.slideOffset : 0;
        float fadeAlpha = fadeAnimation.isEnabled() ? entry.fadeAlpha : 1.0f;

        float textX = x + slideOffset;
        if (showIcon.isEnabled()) {
            textX += iconSizePx + gap;
        }
        float textY = y + 2;

        String effectName = effectDisplayName(entry.effect);
        String displayText = showDuration.isEnabled()
                ? effectName + " " + formatTime(entry.effect) : effectName;

        boolean isLowTime = !entry.effect.isInfinite() && entry.effect.getDuration() <= lowTimeThreshold.getValue() * 20;
        float pulseValue = isLowTime && pulseOnLowTime.isEnabled() ? entry.pulseAnimation : 0;

        int textAlpha = (int) (255 * fadeAlpha);
        int pulseBoost = (int) (50 * pulseValue);
        Color textColor = new Color(
                Math.min(255, 255 + pulseBoost),
                Math.max(0, 255 - pulseBoost),
                Math.max(0, 255 - pulseBoost),
                textAlpha);

        Renderer.get().drawText(context, displayText, textX, textY, fontSize, textColor, true);

        if (showProgressBar.isEnabled()) {
            float barY = y + height - progressHeight - 2;
            float barWidth = width;

            if (!entry.hasInitialDuration) {
                entry.initialMaxDuration = entry.effect.getDuration();
                entry.hasInitialDuration = true;
            }

            float progress = Math.max(0, Math.min(1, entry.smoothProgress));

            Color barBgColor = new Color(50, 50, 50, (int) (150 * fadeAlpha));
            Renderer.get().drawRect(context, textX - (showIcon.isEnabled() ? iconSizePx + gap : 0), barY, barWidth,
                    progressHeight, barBgColor);

            Color barColor;
            if (coloredProgressBar.isEnabled()) {
                if (entry.effect.getEffectType().value().isBeneficial()) {
                    barColor = new Color(50, 255, 50, (int) (200 * fadeAlpha));
                } else {
                    barColor = new Color(255, 50, 50, (int) (200 * fadeAlpha));
                }

                if (isLowTime && pulseOnLowTime.isEnabled()) {
                    int brightness = (int) (255 * (0.7f + 0.3f * pulseValue));
                    barColor = new Color(brightness, 50, 50, (int) (200 * fadeAlpha));
                }
            } else {
                barColor = new Color(255, 255, 255, (int) (200 * fadeAlpha));
            }

            float filledWidth = barWidth * progress;
            Renderer.get().drawRect(context, textX - (showIcon.isEnabled() ? iconSizePx + gap : 0), barY, filledWidth,
                    progressHeight, barColor);
        }
    }

    private String effectDisplayName(StatusEffectInstance effect) {
        String name = cleanName(effect.getEffectType().value().getName().getString());
        int amplifier = effect.getAmplifier();
        return showAmplifier.isEnabled() && amplifier > 0 ? name + " " + toRomanNumeral(amplifier + 1) : name;
    }

    /**
     * Server resource packs often rename effects with colour codes and a private-use icon glyph
     * ("§f §rSpeed"); the HUD font draws those literally, so strip the codes and any glyph it cannot show.
     */
    static String cleanName(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§') { i++; continue; }
            if (Character.isHighSurrogate(c) || Character.isLowSurrogate(c)) continue;
            if (c >= '' && c <= '') continue;
            if (c == '�' || Character.isISOControl(c)) continue;
            out.append(c);
        }
        return out.toString().trim();
    }

    private String formatTime(StatusEffectInstance effect) {
        if (effect.isInfinite()) return "Infinite";
        int ticks = effect.getDuration();
        int seconds = ticks / 20;
        int minutes = seconds / 60;
        seconds = seconds % 60;

        String mode = timeFormat.getCurrentMode();
        switch (mode) {
            case "Minutes:Seconds":
                return String.format("%d:%02d", minutes, seconds);
            case "Seconds Only":
                return String.format("%ds", ticks / 20);
            case "Both":
                return String.format("%d:%02d (%ds)", minutes, seconds, ticks / 20);
            default:
                return String.format("%d:%02d", minutes, seconds);
        }
    }

    private String toRomanNumeral(int number) {
        switch (number) {
            case 1:
                return "I";
            case 2:
                return "II";
            case 3:
                return "III";
            case 4:
                return "IV";
            case 5:
                return "V";
            case 6:
                return "VI";
            case 7:
                return "VII";
            case 8:
                return "VIII";
            case 9:
                return "IX";
            case 10:
                return "X";
            default:
                return String.valueOf(number);
        }
    }

    private void renderPlaceholder(DrawContext context, MinecraftClient mc) {
        final int padding = 8;
        final int iconSizePx = (int) iconSize.getValue();
        final int gap = 6;
        final float fontSize = 12.0f;
        final int spacingPx = (int) spacing.getValue();
        final int progressHeight = (int) progressBarHeight.getValue();
        final int headerHeight = showHeader.isEnabled() ? 15 : 0;

        String[] sampleEffects = showDuration.isEnabled()
                ? (showAmplifier.isEnabled()
                    ? new String[] { "Speed II 1:23", "Regeneration 0:45", "Strength 2:10" }
                    : new String[] { "Speed 1:23", "Regeneration 0:45", "Strength 2:10" })
                : (showAmplifier.isEnabled()
                    ? new String[] { "Speed II", "Regeneration", "Strength" }
                    : new String[] { "Speed", "Regeneration", "Strength" });

        int maxTextWidth = 0;
        for (String effect : sampleEffects) {
            int textWidth = (int) Renderer.get().getTextWidth(effect, fontSize);
            maxTextWidth = Math.max(maxTextWidth, textWidth);
        }

        int contentWidth = maxTextWidth;
        if (showIcon.isEnabled()) {
            contentWidth += iconSizePx + gap;
        }

        int singleEffectHeight = Math.max(iconSizePx, (int) fontSize + 2);
        if (showProgressBar.isEnabled()) {
            singleEffectHeight += progressHeight + 2;
        }

        int totalWidth = contentWidth + padding * 2;
        int totalHeight = headerHeight + (sampleEffects.length * singleEffectHeight)
                + ((sampleEffects.length - 1) * spacingPx) + padding * 2;

        setWidth(totalWidth);
        setHeight(totalHeight);

        float baseX = (float) getX();
        float baseY = (float) getY();
        Color bgColor = new Color((int) (bgOpacity.getValue() * 255) << 24 | HudTheme.backgroundRgb(), true);
        float radius = (float) cornerRadius.getValue();

        if (glowEffect.isEnabled()) {
            int numGlowLayers = 12;
            for (int i = numGlowLayers; i >= 1; i--) {
                float layerProgress = (float) i / numGlowLayers;
                float layerSize = (float) glowSize.getValue() * layerProgress;
                float glowAlpha = (float) glowIntensity.getValue() * (1.0f - layerProgress) / numGlowLayers;
                Color glowColor = new Color(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(),
                        (int) (glowAlpha * 255));
                Renderer.get().drawRoundedRectStyled(
                        context,
                        baseX - layerSize, baseY - layerSize,
                        totalWidth + layerSize * 2, totalHeight + layerSize * 2,
                        radius + layerSize, glowColor,
                        GuiShaderStyle.GLOW);
            }
        }

        Renderer.get().drawRoundedRectStyled(context, baseX, baseY, totalWidth, totalHeight, radius, bgColor, GuiShaderStyle.HUD);
        Renderer.get().drawRoundedRectOutline(context, baseX, baseY, totalWidth, totalHeight, radius, 1.0f,
                HudTheme.rimColor(70));
        Renderer.get().drawRect(context, baseX, baseY + 4f, 2f, Math.max(1f, totalHeight - 8f), HudTheme.accentColor());
        if (showHeader.isEnabled()) {
            Renderer.get().drawText(context, "ACTIVE EFFECTS", baseX + padding, baseY + 5f,
                    8.0f, HudTheme.mutedColor(), true);
        }

        float currentY = baseY + padding + headerHeight;
        float[] progressValues = { 0.7f, 0.3f, 0.85f };
        Color[] effectColors = {
                new Color(50, 255, 50, 200),
                new Color(50, 255, 50, 200),
                new Color(255, 50, 50, 200)
        };

        for (int i = 0; i < sampleEffects.length; i++) {
            float textX = baseX + padding;
            if (showIcon.isEnabled()) {
                textX += iconSizePx + gap;
            }
            float textY = currentY + 2;

            Renderer.get().drawText(context, sampleEffects[i], textX, textY, fontSize, HudTheme.textColor(), true);

            if (showColorMark.isEnabled()) {
                Renderer.get().drawRect(context, baseX + 3f, currentY + 2f, 2f,
                        Math.max(2f, singleEffectHeight - 4f), effectColors[i]);
            }

            if (showProgressBar.isEnabled()) {
                float barY = currentY + singleEffectHeight - progressHeight - 2;
                float barWidth = contentWidth;
                float barX = baseX + padding;

                Renderer.get().drawRect(context, barX, barY, barWidth, progressHeight, new Color(50, 50, 50, 150));

                float filledWidth = barWidth * progressValues[i];
                Color barColor = coloredProgressBar.isEnabled() ? effectColors[i] : new Color(255, 255, 255, 200);
                Renderer.get().drawRect(context, barX, barY, filledWidth, progressHeight, barColor);
            }

            currentY += singleEffectHeight + spacingPx;
        }

        if (showIcon.isEnabled() && mc.player != null) {
            float iconY = baseY + padding + headerHeight;

            var effects = new net.minecraft.registry.entry.RegistryEntry[] {
                    net.minecraft.entity.effect.StatusEffects.SPEED,
                    net.minecraft.entity.effect.StatusEffects.REGENERATION,
                    net.minecraft.entity.effect.StatusEffects.STRENGTH
            };

            for (Object obj : effects) {
                @SuppressWarnings("unchecked")
                var effect = (net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.effect.StatusEffect>) obj;
                Identifier effectId = Registries.STATUS_EFFECT.getId(effect.value());
                if (effectId != null) {
                    Identifier iconTexture = Identifier.of(effectId.getNamespace(),
                            "textures/mob_effect/" + effectId.getPath() + ".png");
                    context.drawTexture(RenderPipelines.GUI_TEXTURED, iconTexture,
                            (int) (baseX + padding), (int) iconY,
                            0.0f, 0.0f,
                            iconSizePx, iconSizePx,
                            18, 18,
                            18, 18);
                }
                iconY += singleEffectHeight + spacingPx;
            }
        }
    }

    private class EffectEntry {
        StatusEffectInstance effect;
        float slideOffset = -50.0f;
        float fadeAlpha = 0.0f;
        float pulseAnimation = 0.0f;
        boolean fadeOut = false;
        float pulseDirection = 1.0f;
        int initialMaxDuration = 0;
        boolean hasInitialDuration = false;
        float targetY = 0.0f;
        float currentY = 0.0f;
        float smoothProgress = 1.0f;

        public EffectEntry(StatusEffectInstance effect) {
            this.effect = effect;
        }

        public void update(float deltaTime) {
            if (slideAnimation.isEnabled() && !fadeOut) {
                slideOffset += (0 - slideOffset) * deltaTime * 8.0f;
                if (Math.abs(slideOffset) < 0.5f) {
                    slideOffset = 0;
                }
            }

            if (fadeAnimation.isEnabled()) {
                if (fadeOut) {
                    fadeAlpha -= deltaTime * 3.0f;
                    if (fadeAlpha < 0)
                        fadeAlpha = 0;
                } else {
                    fadeAlpha += deltaTime * 4.0f;
                    if (fadeAlpha > 1.0f)
                        fadeAlpha = 1.0f;
                }
            } else {
                fadeAlpha = fadeOut ? 0.0f : 1.0f;
            }

            currentY += (targetY - currentY) * deltaTime * 10.0f;
            if (Math.abs(targetY - currentY) < 0.5f) {
                currentY = targetY;
            }

            if (!effect.isInfinite() && effect.getDuration() > initialMaxDuration) {
                initialMaxDuration = effect.getDuration(); // effect was refreshed or extended
            }
            int maxDuration = effect.getEffectType().value().isInstant() ? 1 : initialMaxDuration;
            if (effect.isInfinite()) {
                smoothProgress = 1.0f;
            } else if (maxDuration > 0) {
                float actualProgress = (float) effect.getDuration() / maxDuration;
                smoothProgress += (actualProgress - smoothProgress) * deltaTime * 5.0f;
                if (Math.abs(actualProgress - smoothProgress) < 0.01f) {
                    smoothProgress = actualProgress;
                }
            }

            if (pulseOnLowTime.isEnabled() && !effect.isInfinite() && effect.getDuration() <= lowTimeThreshold.getValue() * 20) {
                pulseAnimation += pulseDirection * deltaTime * 3.0f;
                if (pulseAnimation >= 1.0f) {
                    pulseAnimation = 1.0f;
                    pulseDirection = -1.0f;
                } else if (pulseAnimation <= 0.0f) {
                    pulseAnimation = 0.0f;
                    pulseDirection = 1.0f;
                }
            } else {
                pulseAnimation = 0;
                pulseDirection = 1.0f;
            }
        }
    }
}
