package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import interstellarTikiTiks.apexx.client.enemy.EnemyManager;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.registry.Registries;

import java.util.List;

/**
 * The fixed set of fields a script can read from an entity ({@code e.health}, {@code ev.player.name}).
 * This list is the whole surface: there is no reflection, and an unknown field reads as null.
 */
final class ScriptFields {
    private ScriptFields() { }

    /** Field names, in reference order (shown in the IDE). */
    static final List<String> NAMES = List.of(
            "name", "type", "id", "uuid", "x", "y", "z", "eyeY", "yaw", "pitch", "velX", "velY", "velZ", "speed",
            "distance", "onGround", "alive", "isPlayer", "isSelf", "isFriend", "isEnemy", "sneaking", "sprinting",
            "inWater", "inLava", "onFire", "invisible", "age", "width", "height",
            "health", "maxHealth", "absorption", "armor", "hurtTime", "blocking", "using", "activeItem",
            "held", "offhand", "fallDistance", "swinging", "owner");

    static Value get(Object o, String field) {
        if (!(o instanceof Entity e)) return Value.NULL;
        MinecraftClient mc = MinecraftClient.getInstance();
        LivingEntity le = e instanceof LivingEntity l ? l : null;
        switch (field) {
            case "name": return Value.string(e.getName().getString());
            case "type": return Value.string(Registries.ENTITY_TYPE.getId(e.getType()).getPath());
            case "id": return Value.number(e.getId());
            case "uuid": return Value.string(e.getUuidAsString());
            case "x": return Value.number(e.getX());
            case "y": return Value.number(e.getY());
            case "z": return Value.number(e.getZ());
            case "eyeY": return Value.number(e.getEyeY());
            case "yaw": return Value.number(e.getYaw());
            case "pitch": return Value.number(e.getPitch());
            case "velX": return Value.number(e.getVelocity().x);
            case "velY": return Value.number(e.getVelocity().y);
            case "velZ": return Value.number(e.getVelocity().z);
            case "speed": {
                var v = e.getVelocity();
                return Value.number(Math.sqrt(v.x * v.x + v.z * v.z));
            }
            case "distance": return Value.number(mc == null || mc.player == null ? 0 : mc.player.distanceTo(e));
            case "onGround": return Value.bool(e.isOnGround());
            case "alive": return Value.bool(e.isAlive() && !e.isRemoved());
            case "isPlayer": return Value.bool(e instanceof PlayerEntity);
            case "isSelf": return Value.bool(mc != null && e == mc.player);
            case "isFriend": return Value.bool(e instanceof PlayerEntity && FriendManager.getInstance().isFriend(e.getUuid()));
            case "isEnemy": return Value.bool(e instanceof PlayerEntity && EnemyManager.getInstance().isEnemy(e.getUuid()));
            case "sneaking": return Value.bool(e.isSneaking());
            case "sprinting": return Value.bool(e.isSprinting());
            case "inWater": return Value.bool(e.isTouchingWater());
            case "inLava": return Value.bool(e.isInLava());
            case "onFire": return Value.bool(e.isOnFire());
            case "invisible": return Value.bool(e.isInvisible());
            case "age": return Value.number(e.age);
            case "width": return Value.number(e.getWidth());
            case "height": return Value.number(e.getHeight());
            case "fallDistance": return Value.number(e.fallDistance);
            case "owner": return e instanceof ProjectileEntity p ? Value.entity(p.getOwner()) : Value.NULL;
            default: break;
        }
        if (le == null) return Value.NULL;
        switch (field) {
            case "health": return Value.number(le.getHealth());
            case "maxHealth": return Value.number(le.getMaxHealth());
            case "absorption": return Value.number(le.getAbsorptionAmount());
            case "armor": return Value.number(le.getArmor());
            case "hurtTime": return Value.number(le.hurtTime);
            case "blocking": return Value.bool(le.isBlocking());
            case "using": return Value.bool(le.isUsingItem());
            case "activeItem": return Value.string(le.isUsingItem() ? ScriptEvents.itemId(le.getActiveItem()) : "air");
            case "held": return Value.string(ScriptEvents.itemId(le.getMainHandStack()));
            case "offhand": return Value.string(ScriptEvents.itemId(le.getOffHandStack()));
            case "swinging": return Value.bool(le.handSwinging);
            default: return Value.NULL;
        }
    }
}
