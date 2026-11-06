package interstellarTikiTiks.apexx.client.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayerEntity.class)
public interface ClientPlayerEntityAccessor {

    @Mutable
    @Accessor("lastYawClient")
    void setLastYawClient(float yaw);

    @Mutable
    @Accessor("lastPitchClient")
    void setLastPitchClient(float pitch);

    @Accessor("lastYawClient")
    float getLastYawClient();

    @Accessor("lastPitchClient")
    float getLastPitchClient();

    /** Position carried by the last movement packet: what the server holds for this player. */
    @Accessor("lastXClient")
    double getLastXClient();

    @Accessor("lastYClient")
    double getLastYClient();

    @Accessor("lastZClient")
    double getLastZClient();

    /** The sprint state last sent to the server (updated only by {@code sendSprintingPacket}). */
    @Accessor("lastSprinting")
    boolean getLastSprinting();
}
