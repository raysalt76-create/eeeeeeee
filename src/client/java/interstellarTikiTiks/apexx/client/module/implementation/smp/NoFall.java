package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * NoFall (Blatant) - takes no fall damage. Once the fall is long enough to matter but still short of
 * the 3-block damage threshold, it tells the server the player is on the ground, which resets the
 * server's fall distance, and resets the client's own count to match. Repeats every tick of the fall.
 *
 * <p>Servers with movement checks flag a ground claim in mid-air; this is made for servers with none.</p>
 */
public final class NoFall extends Module {

    private final SliderSetting triggerDistance = new SliderSetting("Trigger Distance", 2.0, 1.0, 2.9, 1)
            .setDescription("Blocks fallen before the ground claim is sent. Vanilla fall damage starts above 3, so keep it below that.");
    private final BooleanSetting skipElytra = new BooleanSetting("Skip While Gliding", true)
            .setDescription("Does nothing while flying with an elytra, so landing speed damage rules stay vanilla.");

    public NoFall() {
        super("NoFall", "Cancels fall damage: during a long fall it tells the server you are on the ground before the damage threshold, over and over, so you land unhurt. Meant for use with Flight. Blatant: any server with movement checks will flag it.", Category.BLATANT);
        addSetting(triggerDistance);
        addSetting(skipElytra);
    }

    @Override
    public void onTickPost() {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        if (player.isOnGround() || player.isSpectator() || player.isCreative() || player.hasVehicle()
                || player.isTouchingWater() || player.isInLava() || player.isClimbing()
                || player.getAbilities().flying || player.hasStatusEffect(StatusEffects.SLOW_FALLING)
                || player.hasStatusEffect(StatusEffects.LEVITATION)) {
            return;
        }
        if (skipElytra.isEnabled() && player.isGliding()) return;
        if (player.fallDistance < triggerDistance.getValue()) return;
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, player.horizontalCollision));
        player.fallDistance = 0;
    }
}
