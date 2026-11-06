package interstellarTikiTiks.apexx.client.util.render;

import net.minecraft.client.render.VertexConsumer;

/**
 * A vertex consumer that multiplies the alpha of every colour written through it, so a block mesh can
 * be drawn translucent without touching the renderer that produces it.
 */
public final class AlphaVertexConsumer implements VertexConsumer {

    private final VertexConsumer delegate;
    private final int opacity;

    public AlphaVertexConsumer(VertexConsumer delegate, int opacity255) {
        this.delegate = delegate;
        this.opacity = Math.max(0, Math.min(255, opacity255));
    }

    @Override
    public VertexConsumer vertex(float x, float y, float z) {
        delegate.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        delegate.color(red, green, blue, alpha * opacity / 255);
        return this;
    }

    @Override
    public VertexConsumer color(int argb) {
        int alpha = (argb >>> 24) * opacity / 255;
        delegate.color((alpha << 24) | (argb & 0x00FFFFFF));
        return this;
    }

    @Override
    public VertexConsumer texture(float u, float v) {
        delegate.texture(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlay(int u, int v) {
        delegate.overlay(u, v);
        return this;
    }

    @Override
    public VertexConsumer light(int u, int v) {
        delegate.light(u, v);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        delegate.normal(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer lineWidth(float width) {
        delegate.lineWidth(width);
        return this;
    }
}
