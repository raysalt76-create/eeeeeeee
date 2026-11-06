package interstellarTikiTiks.apexx.client.module.implementation.movement;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class SpearAssist extends Module {

    private final ModeSetting aimMath = new ModeSetting("Aim Style", "Regular", "Regular", "Blatant", "WindMouse")
            .setDescription("How the view turns toward the target. Regular: smooth, eased turning. Blatant: same smooth engine but meant for fast speeds (use with care). WindMouse: human-like overshoot-and-correct motion; V Speed then drives the pitch axis separately.");
    private final ModeSetting aimPoint = new ModeSetting("Aim Point", "Straight", "Straight", "Closest")
            .setDescription("Where on the target to aim. Straight: the middle of their body. Closest: the point of their hitbox nearest your eyes, which needs less turning.");
    private final ModeSetting targetPriority = new ModeSetting("Target Priority", "Nearest", "Nearest", "FOV", "HurtTime")
            .setDescription("Which target wins when several qualify. Nearest: shortest distance. FOV: closest to your crosshair. HurtTime: the one that can be damaged soonest.");
    private final BooleanSetting randomize = new BooleanSetting("Randomize", false)
            .setDescription("Adds small random variation to the aim angles so the tracking path is not perfectly mechanical.");
    private final BooleanSetting silent = new BooleanSetting("Silent Aim", false)
            .setDescription("Turns only the rotation the server sees; your camera stays where you put it. Easier for servers to detect than visible aim.");
    private final BooleanSetting wallBypass = new BooleanSetting("Wall Bypass", false)
            .setDescription("Also targets players you cannot see because blocks are in the way. Off: only targets in line of sight are chosen.");
    private final SliderSetting hSpeed = new SliderSetting("H Speed", 30.0, 0.0, 300.0, 1)
            .setDescription("How fast the view turns left/right toward the target, in degrees per tick. Lower values look more human.");
    private final SliderSetting vSpeed = new SliderSetting("V Speed", 30.0, 0.0, 300.0, 1)
            .setDescription("How fast the view turns up/down toward the target, in degrees per tick. Only separate from H Speed in WindMouse style.");
    private final SliderSetting range = new SliderSetting("Range", 28.0, 1.0, 128.0, 1)
            .setDescription("Farthest a target can be, in blocks, to be aimed at while you charge the spear.");
    private final SliderSetting fov = new SliderSetting("FOV", 90.0, 0.0, 360.0, 1)
            .setDescription("Width of the cone around your crosshair, in degrees, in which targets are picked up. 360 = all around you.");
    private final SliderSetting multipoint = new SliderSetting("Multipoint", 50.0, 0.0, 100.0, 1)
            .setDescription("Blends the Aim Point toward the nearest point of the target's hitbox: 0% = pure Aim Point, 100% = always the nearest point.");

    private final SliderSetting reactionDelay = new SliderSetting("Reaction Delay (ms)", 70, 0, 400, 0).withJitter(20)
            .setSection("Delays")
            .setDescription("Wait after a target is picked (or the charge starts) before the aim begins turning, in milliseconds. 0 = turn at once.");

    public SpearAssist() {
        super("SpearAssist", "While you hold a charging spear, smoothly turns your aim onto the best target in range and FOV so the charge lands. Stops aiming the moment you stop charging. Uses the shared rotation engine, so visible turning speed and style are fully configurable.", Category.MOVEMENT);
        addSetting(aimMath);
        addSetting(aimPoint);
        addSetting(targetPriority);
        addSetting(randomize);
        addSetting(silent);
        addSetting(wallBypass);
        addSetting(hSpeed);
        addSetting(vSpeed);
        addSetting(range);
        addSetting(fov);
        addSetting(multipoint);
        addSetting(reactionDelay);
    }

    @Override
    public void onDisable() {
        RotationManager.clearTarget(this);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            RotationManager.clearTarget(this);
            return;
        }

        if (!isUsingSpear()) {
            RotationManager.clearTarget(this);
            return;
        }

        LivingEntity target = getTarget();
        if (target != null) {
            final Vec3d targetVec = getAimPoint(target);
            // Aim Style: WindMouse -> the human overshoot-and-correct engine; otherwise SMOOTH.
            // V Speed drives the pitch axis via setWindSpeeds so it is no longer dead (only hSpeed
            // was ever passed). Both were registered knobs that did nothing.
            RotationManager.RotationMode aimStyle = aimMath.getCurrentMode().equals("WindMouse")
                    ? RotationManager.RotationMode.WIND
                    : RotationManager.RotationMode.SMOOTH;
            if (aimStyle == RotationManager.RotationMode.WIND) {
                RotationManager.setWindSpeeds(this, 120.0 + hSpeed.getValue() * 4.0, 120.0 + vSpeed.getValue() * 4.0);
            }
            RotationManager.setRotationSupplier(this, RotationManager.Priority.NORMAL, () -> targetVec,
                    hSpeed.getValue(), aimStyle, randomize.isEnabled() ? 1.0 : 0.0, (long) reactionDelay.sample(),
                    silent.isEnabled(), false);
        } else {
            RotationManager.clearTarget(this);
        }
    }

    private boolean isUsingSpear() {
        if (mc.player.isUsingItem()) {
            ItemStack usingItem = mc.player.getActiveItem();
            if (usingItem.isEmpty()) return false;
            String id = Registries.ITEM.getId(usingItem.getItem()).toString().toLowerCase();
            return id.equals("minecraft:wooden_spear")
                    || id.equals("minecraft:stone_spear")
                    || id.equals("minecraft:copper_spear")
                    || id.equals("minecraft:iron_spear")
                    || id.equals("minecraft:golden_spear")
                    || id.equals("minecraft:diamond_spear")
                    || id.equals("minecraft:netherite_spear")
                    || id.endsWith("_spear") || id.contains("spear");
        }
        return false;
    }

    private LivingEntity getTarget() {
        double r = range.getValue();
        List<LivingEntity> targets = mc.world.getEntitiesByClass(LivingEntity.class, 
                mc.player.getBoundingBox().expand(r, r, r),
                e -> e != mc.player && e.isAlive() && mc.player.distanceTo(e) <= r);

        if (!wallBypass.isEnabled()) {
            targets = targets.stream().filter(mc.player::canSee).collect(Collectors.toList());
        }

        targets = targets.stream().filter(e -> getFOVAngle(e) <= fov.getValue() / 2.0).collect(Collectors.toList());

        if (targets.isEmpty()) {
            return null;
        }

        switch (targetPriority.getCurrentMode()) {
            case "FOV":
                targets.sort(Comparator.comparingDouble(this::getFOVAngle));
                break;
            case "HurtTime":
                targets.sort(Comparator.comparingInt(e -> e.hurtTime));
                break;
            case "Nearest":
            default:
                targets.sort(Comparator.comparingDouble(e -> mc.player.distanceTo(e)));
                break;
        }

        return targets.get(0);
    }

    private double getFOVAngle(LivingEntity entity) {
        Vec3d vec = new Vec3d(entity.getX(), entity.getY(), entity.getZ()).subtract(new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ())).normalize();
        double yaw = interstellarTikiTiks.apexx.client.util.combat.AimGeometry.yawDegrees(vec.x, vec.z);
        return Math.abs(MathHelper.wrapDegrees(yaw - mc.player.getYaw()));
    }

    private Vec3d getAimPoint(LivingEntity entity) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d ePos = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
        Vec3d closest = new Vec3d(
                MathHelper.clamp(eye.x, ePos.x - entity.getWidth() / 2, ePos.x + entity.getWidth() / 2),
                MathHelper.clamp(eye.y, ePos.y, ePos.y + entity.getHeight()),
                MathHelper.clamp(eye.z, ePos.z - entity.getWidth() / 2, ePos.z + entity.getWidth() / 2)
        );
        Vec3d straight = ePos.add(0, entity.getHeight() / 2.0, 0);
        Vec3d base = aimPoint.getCurrentMode().equals("Closest") ? closest : straight;
        // Multipoint blends the base aim toward the nearest reachable hitbox point (0 = base,
        // 100 = fully closest). Was a registered slider that nothing read.
        double mp = MathHelper.clamp(multipoint.getValue() / 100.0, 0.0, 1.0);
        return base.lerp(closest, mp);
    }
}
