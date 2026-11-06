package me.sootysplash.box.mixin;

import java.awt.Color;
import me.sootysplash.box.Config;
import me.sootysplash.box.Main;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.client.render.debug.EntityHitboxDebugRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityHitboxDebugRenderer.class)
public abstract class HitBoxRenderMixin {
    @Inject(method = "drawHitbox(Lnet/minecraft/entity/Entity;FZ)V", at = @At("HEAD"), cancellable = true)
    private void onDrawHitbox(Entity entity, float tickProgress, boolean inLocalServer, CallbackInfo ci) {
        if (inLocalServer) {
            return;
        }
        Config config = Config.getInstance();
        if (!config.enabled) {
            return;
        }
        ci.cancel();
        if (config.hideFireworks && (entity instanceof FireworkRocketEntity)) {
            return;
        }
        MinecraftClient client = Main.mc != null ? Main.mc : MinecraftClient.getInstance();
        float lineWidth = (client.player == null || ((double) client.player.distanceTo(entity)) <= config.distFor2) ? config.line1 : config.line2;
        render_1_21_1_boxes(lineWidth, entity, tickProgress,
                new Color(config.eyeColor, true),
                new Color(config.lookColor, true),
                new Color(config.hitBoxColor, true),
                new Color(config.targetBoxColor, true),
                new Color(config.hurtBoxColor, true),
                new Color(config.outlineColor, true),
                config.changeTargetColor,
                config.hitBoxHurt,
                config.renderEyeHeight,
                config.renderLookDir,
                config.lineLookDir,
                config.outlineEnabled,
                config.outlineMultiplier);
    }

    @Unique
    private static void render_1_21_1_boxes(float lineWidth, Entity entity, float tickProgress,
                                            Color eyeHeight, Color lookDir, Color main,
                                            Color ifTarget, Color ifHurt, Color outlineColor,
                                            boolean targetCol, boolean hurtCol,
                                            boolean renderEyeHeight, boolean renderLookDir,
                                            boolean lineLookDir, boolean outlineEnabled,
                                            float outlineMultiplier) {
        Vec3d vec3d = entity.getEntityPos();
        Vec3d vec3d2 = entity.getLerpedPos(tickProgress);
        Vec3d vec3d3 = vec3d2.subtract(vec3d);

        Color color;
        if (entity instanceof LivingEntity le && le.hurtTime != 0 && hurtCol) {
            color = ifHurt;
        } else {
            MinecraftClient client = Main.mc != null ? Main.mc : MinecraftClient.getInstance();
            if (targetCol && client.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() == entity) {
                color = ifTarget;
            } else {
                color = main;
            }
        }

        int i = color.getRGB();

        if (outlineEnabled) {
            GizmoDrawing.box(entity.getBoundingBox().offset(vec3d3), DrawStyle.stroked(outlineColor.getRGB(), lineWidth * outlineMultiplier));
        }

        GizmoDrawing.box(entity.getBoundingBox().offset(vec3d3), DrawStyle.stroked(i, lineWidth));
        GizmoDrawing.point(vec3d2, i, 2.0f);

        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            float f = Math.min(vehicle.getWidth(), entity.getWidth()) / 2.0f;
            Vec3d ridingPos = vehicle.getPassengerRidingPos(entity).add(vec3d3);
            GizmoDrawing.box(new Box(ridingPos.x - f, ridingPos.y, ridingPos.z - f,
                    ridingPos.x + f, ridingPos.y + 0.0625d, ridingPos.z + f), DrawStyle.stroked(-256, lineWidth));
        }

        if (entity instanceof LivingEntity && renderEyeHeight) {
            Box box = entity.getBoundingBox().offset(vec3d3);
            GizmoDrawing.box(new Box(box.minX, box.minY + entity.getStandingEyeHeight() - 0.009999999776482582d, box.minZ,
                    box.maxX, box.minY + entity.getStandingEyeHeight() + 0.009999999776482582d, box.maxZ),
                    DrawStyle.stroked(eyeHeight.getRGB(), lineWidth));
        }

        if (entity instanceof EnderDragonEntity enderDragon) {
            for (EnderDragonPart part : enderDragon.getBodyParts()) {
                Vec3d partPos = part.getEntityPos();
                Vec3d partLerped = part.getLerpedPos(tickProgress);
                Vec3d partOffset = partLerped.subtract(partPos);
                GizmoDrawing.box(part.getBoundingBox().offset(partOffset), DrawStyle.stroked(ColorHelper.fromFloats(1.0f, 0.25f, 1.0f, 0.0f)));
            }
        }

        Vec3d eyePos = vec3d2.add(0.0d, entity.getStandingEyeHeight(), 0.0d);
        Vec3d rotVec = entity.getRotationVec(tickProgress);
        if (renderLookDir) {
            if (lineLookDir) {
                GizmoDrawing.line(eyePos, eyePos.add(rotVec.multiply(2.0d)), lookDir.getRGB(), lineWidth);
            } else {
                GizmoDrawing.arrow(eyePos, eyePos.add(rotVec.multiply(2.0d)), lookDir.getRGB(), lineWidth);
            }
        }
    }
}
