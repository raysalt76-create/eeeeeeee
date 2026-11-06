package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.util.Identifier;

/** Builder and immutable snapshot for painter-order-preserving UI draw commands. */
public final class UiDisplayList {
    private final List<UiDrawCommand> commands;
    private UiDisplayList(List<UiDrawCommand> commands) { this.commands = List.copyOf(commands); }
    public List<UiDrawCommand> commands() { return commands; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<UiDrawCommand> commands = new ArrayList<>();
        private Builder() { }

        public Builder rect(float x, float y, float width, float height, float radius, int argb) {
            commands.add(new UiDrawCommand.Rect(x, y, width, height, radius, argb)); return this;
        }
        public Builder circle(float centerX, float centerY, float radius, int argb) {
            return rect(centerX - radius, centerY - radius, radius * 2, radius * 2, radius, argb);
        }
        public Builder pill(float x, float y, float width, float height, int argb) {
            return rect(x, y, width, height, height * 0.5f, argb);
        }
        public Builder gradient(float x, float y, float width, float height, float radius,
                                int topLeft, int topRight, int bottomRight, int bottomLeft) {
            commands.add(new UiDrawCommand.GradientRect(x, y, width, height, radius,
                    topLeft, topRight, bottomRight, bottomLeft)); return this;
        }
        public Builder text(float x, float y, float size, int argb, String text) {
            return text(x, y, size, argb, text, UiDrawCommand.TextWeight.REGULAR);
        }
        public Builder text(float x, float y, float size, int argb, String text,
                            UiDrawCommand.TextWeight weight) {
            commands.add(new UiDrawCommand.Text(x, y, size, argb, text, weight)); return this;
        }
        public Builder clipPush(float x, float y, float width, float height, float radius) {
            commands.add(new UiDrawCommand.ClipPush(x, y, width, height, radius)); return this;
        }
        public Builder clipPop() { commands.add(new UiDrawCommand.ClipPop()); return this; }
        public Builder effect(float x, float y, float width, float height, float radius,
                              UiDrawCommand.EffectKind kind, float strength) {
            commands.add(new UiDrawCommand.Effect(x, y, width, height, radius,
                    Objects.requireNonNull(kind, "kind"), strength)); return this;
        }
        /** Liquid glass pane: refracted blurred backdrop, tinted by argb (alpha = tint strength). */
        public Builder glass(float x, float y, float width, float height, float radius,
                             float blurStrength, int tintArgb) {
            commands.add(new UiDrawCommand.Effect(x, y, width, height, radius,
                    UiDrawCommand.EffectKind.GLASS, blurStrength, tintArgb)); return this;
        }
        public Builder blur(float x, float y, float width, float height, float radius, float strength) {
            return effect(x, y, width, height, radius, UiDrawCommand.EffectKind.BLUR, strength);
        }
        public Builder shadow(float x, float y, float width, float height, float radius,
                              float softness, float spread, int argb) {
            commands.add(new UiDrawCommand.Shadow(x, y, width, height, radius, softness, spread, argb));
            return this;
        }
        public Builder bloom(float x, float y, float width, float height, float radius, float strength) {
            return effect(x, y, width, height, radius, UiDrawCommand.EffectKind.BLOOM, strength);
        }
        public Builder border(float x, float y, float width, float height, float radius,
                              float thickness, int argb) {
            commands.add(new UiDrawCommand.Border(x, y, width, height, radius, thickness, argb));
            return this;
        }
        public Builder image(float x, float y, float width, float height, float radius,
                             int argb, Identifier texture) {
            return image(x, y, width, height, radius, argb, texture, 0, 0, 1, 1);
        }
        public Builder image(float x, float y, float width, float height, float radius,
                             int argb, Identifier texture, float u0, float v0, float u1, float v1) {
            commands.add(new UiDrawCommand.Image(x, y, width, height, radius, argb,
                    Objects.requireNonNull(texture, "texture"), u0, v0, u1, v1));
            return this;
        }
        public Builder material(float x, float y, float width, float height, float radius,
                                UiMaterial material) {
            commands.add(new UiDrawCommand.Material(x, y, width, height, radius,
                    Objects.requireNonNull(material, "material")));
            return this;
        }
        /**
         * Interactive GPU fluid pane (Navier-Stokes dye simulation). {@code cursorU/V} is the cursor in
         * pane UV (0..1, top-left); {@code cursorVelU/V} its per-frame UV velocity; {@code dye0..dye2} up
         * to three ARGB dye colours with {@code colorCount} active; {@code opacity} the pane alpha;
         * {@code flow} the speed multiplier; {@code inject} whether the cursor injects fluid this frame.
         */
        public Builder fluid(float x, float y, float width, float height, float radius,
                             float cursorU, float cursorV, float cursorVelU, float cursorVelV,
                             int dye0, int dye1, int dye2, int colorCount,
                             float opacity, float flow, boolean inject) {
            commands.add(new UiDrawCommand.Fluid(x, y, width, height, radius,
                    cursorU, cursorV, cursorVelU, cursorVelV,
                    dye0, dye1, dye2, colorCount, opacity, flow, inject));
            return this;
        }
        public UiDisplayList build() {
            int clipDepth = 0;
            for (UiDrawCommand command : commands) {
                if (command instanceof UiDrawCommand.ClipPush) clipDepth++;
                else if (command instanceof UiDrawCommand.ClipPop && --clipDepth < 0)
                    throw new IllegalStateException("Clip stack underflow");
            }
            if (clipDepth != 0) throw new IllegalStateException("Unclosed UI clip");
            return new UiDisplayList(commands);
        }
    }
}
