package interstellarTikiTiks.apexx.client.friend;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.util.Notifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

public class FriendHelper {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public static boolean toggleTargetedPlayer() {
        PlayerEntity targetedPlayer = getTargetedPlayer();

        if (targetedPlayer == null) {
            return false;
        }

        String playerName = targetedPlayer.getName().getString();

        // The key must never flip an enemy into a friend; that takes an explicit change in the People tab.
        if (interstellarTikiTiks.apexx.client.enemy.EnemyManager.getInstance().isEnemy(targetedPlayer.getUuid())) {
            Notifier.error("Friends", playerName + " is an enemy.");
            return true;
        }

        if (FriendManager.getInstance().isFriend(targetedPlayer.getUuid())) {

            FriendManager.getInstance().removeFriend(targetedPlayer.getUuid());
            Notifier.error("Friends", "Removed " + playerName + ".");
        } else {

            FriendManager.getInstance().addFriend(playerName, targetedPlayer.getUuid());
            Notifier.success("Friends", "Added " + playerName + ".");
        }

        return true;
    }

    public static boolean toggleTargetedEnemy() {
        PlayerEntity targetedPlayer = getTargetedPlayer();
        if (targetedPlayer == null) return false;

        String playerName = targetedPlayer.getName().getString();
        interstellarTikiTiks.apexx.client.enemy.EnemyManager enemies =
                interstellarTikiTiks.apexx.client.enemy.EnemyManager.getInstance();

        if (enemies.isEnemy(targetedPlayer.getUuid())) {
            enemies.removeEnemy(targetedPlayer.getUuid());
            Notifier.error("Enemies", "Removed " + playerName + ".");
        } else if (FriendManager.getInstance().isFriend(targetedPlayer.getUuid())) {
            // Mirror of the friend key: never flip a friend to an enemy by keypress.
            Notifier.error("Enemies", playerName + " is a friend.");
        } else {
            enemies.addEnemy(playerName, targetedPlayer.getUuid());
            Notifier.success("Enemies", "Added " + playerName + ".");
        }
        return true;
    }

    public static PlayerEntity getTargetedPlayer() {
        if (mc.player == null || mc.world == null) {
            return null;
        }

        HitResult hitResult = mc.crosshairTarget;

        if (hitResult != null && hitResult.getType() == HitResult.Type.ENTITY) {
            EntityHitResult entityHit = (EntityHitResult) hitResult;
            Entity entity = entityHit.getEntity();

            if (entity instanceof PlayerEntity && entity != mc.player) {
                return (PlayerEntity) entity;
            }
        }

        return null;
    }

    public static boolean isTargetingFriend() {
        PlayerEntity target = getTargetedPlayer();
        return target != null && FriendManager.getInstance().isFriend(target.getUuid());
    }
}
