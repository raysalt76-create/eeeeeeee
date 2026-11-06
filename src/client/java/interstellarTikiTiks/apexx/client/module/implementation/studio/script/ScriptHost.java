package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ast.Nodes.Block;

/**
 * What the {@link Evaluator} needs from the script that owns it. {@link ScriptModule} implements it in
 * game; unit tests implement it without Minecraft.
 */
public interface ScriptHost {
    /** Live value of the setting at a compile-time slot. */
    Value readSetting(int slot);

    /** Run {@code body} after {@code delayMs} with {@code locals} as its local frame (an {@code after} block). */
    void schedule(Block body, Value[] locals, double delayMs);

    /** The module passed to host functions as their owner; null outside the game. */
    default ScriptModule module() { return null; }
}
