package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import net.minecraft.util.Identifier;

/** Typed, immutable display-list operation. Commands are executed in list order. */
public sealed interface UiDrawCommand permits UiDrawCommand.Rect, UiDrawCommand.GradientRect, UiDrawCommand.Text,
        UiDrawCommand.ClipPush, UiDrawCommand.ClipPop, UiDrawCommand.Effect, UiDrawCommand.Shadow,
        UiDrawCommand.Border, UiDrawCommand.Image, UiDrawCommand.Material, UiDrawCommand.Fluid {
    record Rect(float x, float y, float width, float height, float radius, int argb) implements UiDrawCommand { }
    record GradientRect(float x, float y, float width, float height, float radius,
                        int topLeft, int topRight, int bottomRight, int bottomLeft) implements UiDrawCommand { }
    enum TextWeight { REGULAR, SEMIBOLD }
    record Text(float x, float y, float size, int argb, String text, TextWeight weight) implements UiDrawCommand {
        public Text(float x, float y, float size, int argb, String text) {
            this(x, y, size, argb, text, TextWeight.REGULAR);
        }
        public Text {
            java.util.Objects.requireNonNull(text, "text");
            java.util.Objects.requireNonNull(weight, "weight");
        }
    }
    record ClipPush(float x, float y, float width, float height, float radius) implements UiDrawCommand { }
    record ClipPop() implements UiDrawCommand { }
    enum EffectKind { BLUR, BACKDROP, BLOOM, GLASS }
    record Effect(float x, float y, float width, float height, float radius,
                  EffectKind kind, float strength, int argb) implements UiDrawCommand {
        public Effect(float x, float y, float width, float height, float radius,
                      EffectKind kind, float strength) {
            this(x, y, width, height, radius, kind, strength, 0xFFFFFFFF);
        }
    }
    record Shadow(float x, float y, float width, float height, float radius,
                  float softness, float spread, int argb) implements UiDrawCommand { }
    record Border(float x, float y, float width, float height, float radius,
                  float thickness, int argb) implements UiDrawCommand { }
    record Image(float x, float y, float width, float height, float radius,
                 int argb, Identifier texture, float u0, float v0, float u1, float v1)
            implements UiDrawCommand { }
    record Material(float x, float y, float width, float height, float radius,
                    UiMaterial material) implements UiDrawCommand { }
    /**
     * Interactive Navier-Stokes dye fluid pane. The cursor (UV + per-frame UV velocity) injects dye and
     * velocity splats when {@code inject} is true; up to {@code colorCount} of the three ARGB dye colours
     * are used. {@code opacity} is the overall pane alpha and {@code flow} scales the simulation speed.
     */
    record Fluid(float x, float y, float width, float height, float radius,
                 float cursorU, float cursorV, float cursorVelU, float cursorVelV,
                 int dye0, int dye1, int dye2, int colorCount,
                 float opacity, float flow, boolean inject) implements UiDrawCommand { }
}
