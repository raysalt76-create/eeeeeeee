package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.CustomFontProvider;
import interstellarTikiTiks.apexx.client.rendering.font.MSDFFontRenderer;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(DrawContext.class)
public class DrawContextMixin {
    private static final ThreadLocal<Boolean> APEXX_GLOBAL_FONT_REENTRY = ThreadLocal.withInitial(() -> false);
    private static final int MSDF_FIRST_CHAR = 32;
    private static final int MSDF_LAST_CHAR = 126;
    private static CustomFontProvider cachedCustomFontProvider;
    private static long lastCustomFontProviderUpdate = 0;

    private static CustomFontProvider getCustomFontProvider() {
        long now = System.currentTimeMillis();
        if (now - lastCustomFontProviderUpdate > 1000) {
            cachedCustomFontProvider = ModuleAccess.getHook(CustomFontProvider.class);
            lastCustomFontProviderUpdate = now;
        }
        return cachedCustomFontProvider;
    }

    private record StyleRun(String text, Style style) {}
    private record OrderedRenderData(String plain, MutableText styled, boolean uniformStyle, List<StyleRun> runs) {}

    private static OrderedRenderData apexx$buildOrderedRenderData(OrderedText orderedText, Identifier fontId) {
        if (orderedText == null || fontId == null) {
            return new OrderedRenderData("", Text.empty(), true, List.of());
        }

        StyleSpriteSource.Font font = new StyleSpriteSource.Font(fontId);
        StringBuilder plain = new StringBuilder();
        StringBuilder run = new StringBuilder();
        final Style[] currentStyle = new Style[1];
        final int[] styleChanges = new int[] { 0 };
        MutableText merged = Text.empty();
        List<StyleRun> runs = new ArrayList<>();

        orderedText.accept((index, style, codePoint) -> {
            Style safe = style == null ? Style.EMPTY : style;
            if (currentStyle[0] == null) {
                currentStyle[0] = safe;
            } else if (!safe.equals(currentStyle[0])) {
                if (!run.isEmpty()) {
                    String runText = run.toString();
                    runs.add(new StyleRun(runText, currentStyle[0]));
                    merged.append(Text.literal(runText).setStyle(currentStyle[0].withFont(font)));
                    run.setLength(0);
                }
                currentStyle[0] = safe;
                styleChanges[0]++;
            }
            plain.appendCodePoint(codePoint);
            run.appendCodePoint(codePoint);
            return true;
        });

        if (!run.isEmpty()) {
            Style finalStyle = currentStyle[0] == null ? Style.EMPTY : currentStyle[0];
            String runText = run.toString();
            runs.add(new StyleRun(runText, finalStyle));
            merged.append(Text.literal(runText).setStyle(finalStyle.withFont(font)));
        }

        return new OrderedRenderData(plain.toString(), merged, styleChanges[0] == 0, runs);
    }

    private static Identifier apexx$getGlobalFontId(CustomFontProvider customFont) {
        if (customFont == null || !customFont.isGlobalFontActive()) {
            return null;
        }
        return customFont.activeFontId();
    }

    private boolean apexx$tryRenderGlobalMsdf(CustomFontProvider customFont, String text, int x, int y, int color, boolean shadow) {
        if (text == null || customFont == null || !customFont.isMsdfGlobalActive()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') continue;
            if (ch < MSDF_FIRST_CHAR || ch > MSDF_LAST_CHAR) {
                return false;
            }
        }
        Identifier fontId = customFont.activeMsdfFontId();
        if (fontId == null) {
            return false;
        }
        DrawContext ctx = (DrawContext) (Object) this;
        float scale = 1.0f;
        return MSDFFontRenderer.drawText(ctx, fontId, text, x, y, scale, color, shadow);
    }

    private boolean apexx$tryRenderGlobalMsdfRuns(CustomFontProvider customFont, OrderedRenderData data, int x, int y, int color, boolean shadow) {
        if (customFont == null || data == null || !customFont.isMsdfGlobalActive()) return false;
        Identifier fontId = customFont.activeMsdfFontId();
        if (fontId == null || data.runs() == null || data.runs().isEmpty()) return false;

        float scale = 1.0f;
        float cursorX = x;
        int alpha = color & 0xFF000000;
        DrawContext ctx = (DrawContext) (Object) this;

        for (StyleRun run : data.runs()) {
            String runText = run.text();
            if (runText == null || runText.isEmpty()) continue;
            for (int i = 0; i < runText.length(); i++) {
                char ch = runText.charAt(i);
                if (ch == '\n') continue;
                if (ch < MSDF_FIRST_CHAR || ch > MSDF_LAST_CHAR) {
                    return false;
                }
            }

            int runRgb = run.style() != null && run.style().getColor() != null
                    ? run.style().getColor().getRgb()
                    : (color & 0x00FFFFFF);
            int runColor = alpha | (runRgb & 0x00FFFFFF);
            if (!MSDFFontRenderer.drawText(ctx, fontId, runText, cursorX, y, scale, runColor, shadow)) {
                return false;
            }
            cursorX += MSDFFontRenderer.getTextWidth(fontId, runText) * scale;
        }
        return true;
    }

    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void apexx$applyGlobalFontToString(TextRenderer textRenderer, String text, int x, int y, int color,
            boolean shadow, CallbackInfo ci) {
        if (APEXX_GLOBAL_FONT_REENTRY.get() || text == null) {
            return;
        }

        CustomFontProvider customFont = getCustomFontProvider();
        if (customFont == null || !customFont.isGlobalFontActive()) {
            return;
        }

        APEXX_GLOBAL_FONT_REENTRY.set(true);
        try {
            if (apexx$tryRenderGlobalMsdf(customFont, text, x, y, color, shadow)) {
                ci.cancel();
                return;
            }
        } finally {
            APEXX_GLOBAL_FONT_REENTRY.set(false);
        }

        Identifier fontId = apexx$getGlobalFontId(customFont);
        if (fontId == null) {
            return;
        }

        Text styled = Text.literal(text)
                .setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(fontId)));

        APEXX_GLOBAL_FONT_REENTRY.set(true);
        try {
            ((DrawContext) (Object) this).drawText(textRenderer, styled, x, y, color, shadow);
            ci.cancel();
        } finally {
            APEXX_GLOBAL_FONT_REENTRY.set(false);
        }
    }

    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void apexx$applyGlobalFontToText(TextRenderer textRenderer, Text text, int x, int y, int color,
            boolean shadow, CallbackInfo ci) {
        

        CustomFontProvider customFont = getCustomFontProvider();
        if (APEXX_GLOBAL_FONT_REENTRY.get() || customFont == null || !customFont.isGlobalFontActive() || text == null) {
            return;
        }

        APEXX_GLOBAL_FONT_REENTRY.set(true);
        try {
            if (apexx$tryRenderGlobalMsdf(customFont, text.getString(), x, y, color, shadow)) {
                ci.cancel();
                return;
            }
        } finally {
            APEXX_GLOBAL_FONT_REENTRY.set(false);
        }

        Identifier fontId = apexx$getGlobalFontId(customFont);
        if (fontId == null) return;

        MutableText styled = text.copy().styled(style -> style.withFont(new StyleSpriteSource.Font(fontId)));

        APEXX_GLOBAL_FONT_REENTRY.set(true);
        try {
            ((DrawContext) (Object) this).drawText(textRenderer, styled, x, y, color, shadow);
            ci.cancel();
        } finally {
            APEXX_GLOBAL_FONT_REENTRY.set(false);
        }
    }

    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;IIIZ)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void apexx$applyGlobalFontToOrderedText(TextRenderer textRenderer, OrderedText text, int x, int y, int color,
                                                       boolean shadow, CallbackInfo ci) {
        if (APEXX_GLOBAL_FONT_REENTRY.get() || text == null) {
            return;
        }
        CustomFontProvider customFont = getCustomFontProvider();
        if (customFont == null || !customFont.isGlobalFontActive()) {
            return;
        }

        Identifier fontId = apexx$getGlobalFontId(customFont);
        if (fontId == null) {
            return;
        }
        OrderedRenderData renderData = apexx$buildOrderedRenderData(text, fontId);
        if (renderData.plain().isEmpty()) {
            return;
        }

        APEXX_GLOBAL_FONT_REENTRY.set(true);
        try {
            if (renderData.uniformStyle()) {
                if (apexx$tryRenderGlobalMsdf(customFont, renderData.plain(), x, y, color, shadow)) {
                    ci.cancel();
                    return;
                }
            } else if (apexx$tryRenderGlobalMsdfRuns(customFont, renderData, x, y, color, shadow)) {
                ci.cancel();
                return;
            }
        } finally {
            APEXX_GLOBAL_FONT_REENTRY.set(false);
        }

        APEXX_GLOBAL_FONT_REENTRY.set(true);
        try {
            ((DrawContext) (Object) this).drawText(textRenderer, renderData.styled(), x, y, color, shadow);
            ci.cancel();
        } finally {
            APEXX_GLOBAL_FONT_REENTRY.set(false);
        }
    }

    @Inject(method = "drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;III)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void apexx$bridgeShadowString(TextRenderer textRenderer, String text, int x, int y, int color, CallbackInfo ci) {
        CustomFontProvider customFont = getCustomFontProvider();
        if (customFont == null || !customFont.isGlobalFontActive()) {
            return;
        }
        DrawContext ctx = (DrawContext) (Object) this;
        ctx.drawText(textRenderer, text, x, y, color, true);
        ci.cancel();
    }

    @Inject(method = "drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void apexx$bridgeShadowText(TextRenderer textRenderer, Text text, int x, int y, int color, CallbackInfo ci) {
        CustomFontProvider customFont = getCustomFontProvider();
        if (customFont == null || !customFont.isGlobalFontActive()) {
            return;
        }
        DrawContext ctx = (DrawContext) (Object) this;
        ctx.drawText(textRenderer, text, x, y, color, true);
        ci.cancel();
    }

    @Inject(method = "drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;III)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void apexx$bridgeShadowOrdered(TextRenderer textRenderer, OrderedText text, int x, int y, int color, CallbackInfo ci) {
        CustomFontProvider customFont = getCustomFontProvider();
        if (customFont == null || !customFont.isGlobalFontActive()) {
            return;
        }
        DrawContext ctx = (DrawContext) (Object) this;
        ctx.drawText(textRenderer, text, x, y, color, true);
        ci.cancel();
    }
}
