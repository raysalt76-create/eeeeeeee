package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.util.math.Vec3d;

/** Receives a snapshot after vanilla accepts a jump by the local player. */
public interface LocalJumpListener {
    void onLocalJump(Vec3d position);
}
