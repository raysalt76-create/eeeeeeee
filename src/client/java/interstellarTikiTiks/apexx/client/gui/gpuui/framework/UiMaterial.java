package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import net.minecraft.util.Identifier;

import java.util.Arrays;
import java.util.Objects;

/** Immutable parameters for a custom GPU fragment shader. */
public final class UiMaterial {
    private final Identifier shader;
    private final Identifier texture;
    private final float[] parameters;
    private final int tint;

    public UiMaterial(Identifier shader, Identifier texture, int tint, float... parameters) {
        this.shader = Objects.requireNonNull(shader, "shader");
        this.texture = texture;
        this.tint = tint;
        if (parameters.length > 60) throw new IllegalArgumentException("At most fifteen custom vec4 parameters");
        this.parameters = Arrays.copyOf(parameters, 60);
        for (float value : this.parameters) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite shader parameter");
        }
    }

    public Identifier shader() { return shader; }
    public Identifier texture() { return texture; }
    public int tint() { return tint; }
    public float parameter(int index) { return parameters[index]; }
    public float[] parameters() { return parameters.clone(); }
}
