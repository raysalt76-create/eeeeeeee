package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Shared block-placement helpers for combat modules.
 */
public class ApexxPlacementUtil {

    public static BlockHitResult getStrictBlockHit(MinecraftClient client, BlockPos pos) {
        if (client.world == null || client.player == null) return null;
        
        net.minecraft.util.math.Vec3d eyePos = client.player.getCameraPosVec(1.0F);
        
        for (Direction dir : Direction.values()) {
            net.minecraft.util.math.Vec3d targetVec = net.minecraft.util.math.Vec3d.ofCenter(pos).add(net.minecraft.util.math.Vec3d.of(dir.getVector()).multiply(0.5));
            net.minecraft.world.RaycastContext context = new net.minecraft.world.RaycastContext(eyePos, targetVec, net.minecraft.world.RaycastContext.ShapeType.OUTLINE, net.minecraft.world.RaycastContext.FluidHandling.NONE, client.player);
            BlockHitResult result = client.world.raycast(context);
            if (result != null && result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(pos)) {
                return result;
            }
        }
        
        // Fallback if no direct line of sight to a face
        return new BlockHitResult(net.minecraft.util.math.Vec3d.ofCenter(pos), Direction.UP, pos, false);
    }

    public static boolean interactBlock(MinecraftClient client, BlockHitResult hitResult) {
        if (client == null || client.player == null || client.interactionManager == null || hitResult == null) {
            return false;
        }
        
        setRightClickDelay0(client);
        try {
            BlockHitResult liveHit = BlockPlacementValidator.validateHit(
                    client, hitResult, RotationManager.isSilentRotationActive());
            if (liveHit == null) return false;
            return client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, liveHit).isAccepted();
        } finally {
            setRightClickDelay0(client);
        }
    }

    public static void setRightClickDelay0(MinecraftClient client) {
        ((MinecraftClientAccessor) client).setItemUseCooldown(0);
    }
    
    public static void syncSelectedSlot(MinecraftClient client) {
        if (client != null && client.player != null) {
            interstellarTikiTiks.apexx.client.util.SystemInputSimulator.setHotbarSlot(((interstellarTikiTiks.apexx.client.mixin.PlayerInventoryAccessor)client.player.getInventory()).getSelectedSlot());
        }
    }
    
    public static boolean attackEntity(MinecraftClient client, Entity target) {
        if (client == null || client.player == null || client.interactionManager == null || target == null) {
            return false;
        }
        
        return AttackValidator.dispatchEntityAttack(client, target, false, true);
    }
    
    public static boolean attackCrosshairTarget(MinecraftClient client) {
        if (client == null || client.crosshairTarget == null) {
            return false;
        }
        if (client.crosshairTarget.getType() == HitResult.Type.ENTITY) {
            return attackEntity(client, ((EntityHitResult) client.crosshairTarget).getEntity());
        }
        return false;
    }
    
    public static void releaseUseKey(MinecraftClient client) {
        if (client != null && client.options != null && client.options.useKey != null) {
            client.options.useKey.setPressed(false);
        }
    }
    
    public static void pressUseKey(MinecraftClient client) {
        if (client != null && client.options != null && client.options.useKey != null) {
            client.options.useKey.setPressed(true);
        }
    }
    
    public static void sendStopActionPacket(MinecraftClient client) {
        if (client != null && client.getNetworkHandler() != null) {
            interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.recordUse();
            client.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, Direction.DOWN));
        }
    }
}
