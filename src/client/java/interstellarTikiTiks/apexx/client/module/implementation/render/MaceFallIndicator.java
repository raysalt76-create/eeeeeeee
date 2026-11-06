package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.combat.MaceDamageMath;
import interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator;
import interstellarTikiTiks.apexx.client.util.render.types.RenderTypes;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.awt.Color;
import java.util.Locale;

/**
 * Mace Fall Indicator: while you hold a mace in the air, marks where you will land and shows the smash
 * damage your current fall would deal, plus whether a target below is on your path.
 *
 * <p>Once per tick it steps your fall forward with vanilla air physics (gravity 0.08, vertical drag 0.98,
 * horizontal drag 0.91, no further input) until it meets terrain, accumulating fall distance. The
 * damage uses the 1.21.11 mace formula ({@link MaceDamageMath}) with the held mace's Density and Breach
 * levels; against a target, the hit point is the first tick your eyes come within your entity
 * interaction range of its hitbox, and the number shown is after its armor (before Protection and
 * Resistance).</p>
 */
public final class MaceFallIndicator extends Module {
    private static final int MAX_TICKS = 160;
    private static final int RING_SEGMENTS = 40;

    private final BooleanSetting ring = new BooleanSetting("Landing Ring", true)
            .setDescription("Draws a ring on the ground where you will land.");
    private final BooleanSetting text = new BooleanSetting("Damage Text", true)
            .setDescription("Shows the predicted smash damage under your crosshair.");
    private final BooleanSetting targets = new BooleanSetting("Target Check", true)
            .setDescription("Checks whether a player below is on your fall path and shows the damage after their armor.");
    private final BooleanSetting mobTargets = new BooleanSetting("Mob Targets", false)
            .setDescription("Also checks mobs for Target Check.").setDependsOn("Target Check");
    private final SliderSetting minFall = new SliderSetting("Min Fall", 1.5, 0.0, 10.0, 1)
            .setDescription("Only shows anything once the predicted fall is at least this many blocks.");
    private final ModeSetting colorMode = new ModeSetting("Color Mode", "Theme", "Theme", "Custom")
            .setDescription("Theme uses the ClickGUI accent; Custom uses Custom Color.");
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(255, 170, 60))
            .setDescription("Ring and text colour when Color Mode is Custom.").setDependsOn("Color Mode:Custom");
    private final ColorSetting lethalColor = new ColorSetting("Lethal Color", new Color(255, 50, 50))
            .setDescription("Colour used when the predicted hit would take the target's whole health.")
            .setDependsOn("Target Check");

    // Prediction (client thread, written in onTick, read when rendering).
    private boolean active;
    private double landX, landY, landZ;
    private double landFall;
    private double landDamage;
    private String targetName;
    private double targetDamage;
    private double targetFall;
    private boolean targetLethal;
    private String line1 = "";
    private String line2 = "";

    public MaceFallIndicator() {
        super("MaceFallIndicator", "While you hold a mace in the air, shows where you will land and the smash damage your "
                + "fall would deal (with Density and Breach), and whether a target below is on your path. Visible only to you.",
                Category.RENDER);
        addSetting(ring);
        addSetting(text);
        addSetting(targets);
        addSetting(mobTargets);
        addSetting(minFall);
        addSetting(colorMode);
        addSetting(customColor);
        addSetting(lethalColor);
    }

    @Override
    public boolean rendersInWorld() {
        return ring.isEnabled();
    }

    @Override
    public void onDisable() {
        active = false;
    }

    @Override
    protected void onRuntimeStateReleased() {
        active = false;
    }

    @Override
    public void onTick() {
        active = false;
        if (mc.player == null || mc.world == null) return;
        ItemStack held = mc.player.getMainHandStack();
        if (held.isEmpty() || !held.isOf(Items.MACE)) return;
        if (mc.player.isOnGround() || mc.player.isGliding() || mc.player.getAbilities().flying
                || mc.player.isTouchingWater() || mc.player.isClimbing() || mc.player.hasVehicle()
                || mc.player.hasStatusEffect(StatusEffects.LEVITATION)) {
            return;
        }

        int density = enchantLevel(held, Enchantments.DENSITY);
        int breach = enchantLevel(held, Enchantments.BREACH);
        double attack = mc.player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
        float cooldownNow = mc.player.getAttackCooldownProgress(0.0f);
        float perTick = Math.max(1.0f, mc.player.getAttackCooldownProgressPerTick());
        boolean crit = !mc.player.isSprinting() && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS);
        double reach = mc.player.getEntityInteractionRange();
        boolean slowFall = mc.player.hasStatusEffect(StatusEffects.SLOW_FALLING);
        double gravity = slowFall ? 0.01 : 0.08;

        // Candidate targets: living entities below us within a generous column.
        LivingEntity[] candidates = gatherTargets();

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        Vec3d v = mc.player.getVelocity();
        double vx = v.x, vy = v.y, vz = v.z;
        double fall = Math.max(0.0, mc.player.fallDistance);
        double eye = mc.player.getStandingEyeHeight();
        targetName = null;
        boolean landed = false;
        int landTick = 0;

        for (int t = 1; t <= MAX_TICKS; t++) {
            double nx = px + vx, ny = py + vy, nz = pz + vz;
            if (vy < 0.0) fall -= vy;

            if (targets.isEnabled() && targetName == null && candidates != null) {
                for (LivingEntity c : candidates) {
                    if (c == null) break;
                    Box box = c.getBoundingBox();
                    if (distanceToBox(nx, ny + eye, nz, box) <= reach && ny + eye > box.minY) {
                        float cd = Math.min(1.0f, cooldownNow + t / perTick);
                        double raw = MaceDamageMath.rawSmash(attack, cd, crit && fall > 0.0, fall, density);
                        double after = MaceDamageMath.afterArmor(raw, c.getArmor(),
                                c.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS), breach);
                        targetName = c.getName().getString();
                        targetDamage = after;
                        targetFall = fall;
                        targetLethal = after >= c.getHealth() + c.getAbsorptionAmount();
                        break;
                    }
                }
            }

            HitResult hit = mc.world.raycast(new RaycastContext(new Vec3d(px, py, pz), new Vec3d(nx, ny, nz),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, mc.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
                Vec3d p = bhr.getPos();
                // Fall distance only counts down to the contact point, not the whole tick step.
                if (vy < 0.0) fall += (ny - p.y);
                landX = p.x;
                landY = p.y;
                landZ = p.z;
                landed = true;
                landTick = t;
                break;
            }
            px = nx;
            py = ny;
            pz = nz;
            vx *= 0.91;
            vz *= 0.91;
            vy = (vy - gravity) * 0.98;
        }
        if (!landed) return;

        landFall = fall;
        float landCooldown = Math.min(1.0f, cooldownNow + landTick / perTick);
        landDamage = MaceDamageMath.rawSmash(attack, landCooldown, crit, fall, density);
        boolean showTarget = targetName != null && MaceDamageMath.isSmash(targetFall, false);
        if (Math.max(landFall, showTarget ? targetFall : 0.0) < minFall.getValue()) return;

        line1 = MaceDamageMath.isSmash(landFall, false)
                ? String.format(Locale.ROOT, "Smash %.1f  (fall %.1f)", landDamage, landFall)
                : String.format(Locale.ROOT, "No smash  (fall %.1f)", landFall);
        line2 = showTarget
                ? String.format(Locale.ROOT, "%s %s %.1f%s", targetLethal ? "☠" : "→", targetName,
                targetDamage, breach > 0 ? " (Breach " + breach + ")" : "")
                : "";
        if (!showTarget) targetName = null;
        active = true;
    }

    private LivingEntity[] gatherTargets() {
        if (!targets.isEnabled() || mc.world == null || mc.player == null) return null;
        LivingEntity[] out = null;
        int n = 0;
        Box column = mc.player.getBoundingBox().expand(6.0, 0.0, 6.0).stretch(0.0, -64.0, 0.0);
        for (Entity e : mc.world.getOtherEntities(mc.player, column)) {
            if (!(e instanceof LivingEntity living) || !living.isAlive() || e instanceof ArmorStandEntity) continue;
            if (e instanceof PlayerEntity p) {
                if (p.isSpectator() || FriendManager.getInstance().isFriend(p.getUuid())) continue;
            } else if (!mobTargets.isEnabled() || e.getType().getSpawnGroup() == SpawnGroup.MISC) {
                continue;
            }
            if (out == null) out = new LivingEntity[8];
            if (n == out.length) break;
            out[n++] = living;
        }
        return out;
    }

    private static int enchantLevel(ItemStack stack, RegistryKey<Enchantment> key) {
        ItemEnchantmentsComponent enchants = stack.getEnchantments();
        if (enchants == null || enchants.isEmpty()) return 0;
        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : enchants.getEnchantmentEntries()) {
            if (entry.getKey().matchesKey(key)) return entry.getIntValue();
        }
        return 0;
    }

    private static double distanceToBox(double x, double y, double z, Box b) {
        double dx = Math.max(Math.max(b.minX - x, 0.0), x - b.maxX);
        double dy = Math.max(Math.max(b.minY - y, 0.0), y - b.maxY);
        double dz = Math.max(Math.max(b.minZ - z, 0.0), z - b.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private int baseColor() {
        return 0xFF000000 | ((colorMode.is("Custom") ? customColor.getValue().getRGB() : AccentState.accentArgb()) & 0x00FFFFFF);
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (!active || !ring.isEnabled() || mc.player == null) return;
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        int argb = targetName != null && targetLethal ? 0xFF000000 | lethalColor.getValue().getRGB() : baseColor();
        float r = ((argb >> 16) & 0xFF) / 255.0f, g = ((argb >> 8) & 0xFF) / 255.0f, b = (argb & 0xFF) / 255.0f;
        float cx = (float) (landX - cam.x), cy = (float) (landY - cam.y) + 0.02f, cz = (float) (landZ - cam.z);
        float outer = 0.45f, inner = 0.36f;
        float pulse = 0.65f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 140.0);
        try (BufferAllocator allocator = PooledBufferAllocator.acquire(4096)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            VertexConsumer buf = immediate.getBuffer(RenderTypes.TRIANGLES);
            var m = matrices.peek().getPositionMatrix();
            for (int i = 0; i < RING_SEGMENTS; i++) {
                double a0 = Math.PI * 2.0 * i / RING_SEGMENTS;
                double a1 = Math.PI * 2.0 * (i + 1) / RING_SEGMENTS;
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
                float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                buf.vertex(m, cx + c0 * inner, cy, cz + s0 * inner).color(r, g, b, pulse);
                buf.vertex(m, cx + c0 * outer, cy, cz + s0 * outer).color(r, g, b, pulse);
                buf.vertex(m, cx + c1 * outer, cy, cz + s1 * outer).color(r, g, b, pulse);
                buf.vertex(m, cx + c0 * inner, cy, cz + s0 * inner).color(r, g, b, pulse);
                buf.vertex(m, cx + c1 * outer, cy, cz + s1 * outer).color(r, g, b, pulse);
                buf.vertex(m, cx + c1 * inner, cy, cz + s1 * inner).color(r, g, b, pulse);
                // Faint fill disc.
                buf.vertex(m, cx, cy, cz).color(r, g, b, 0.12f);
                buf.vertex(m, cx + c0 * inner, cy, cz + s0 * inner).color(r, g, b, 0.12f);
                buf.vertex(m, cx + c1 * inner, cy, cz + s1 * inner).color(r, g, b, 0.12f);
            }
            immediate.draw();
        }
    }

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (!active || !text.isEnabled() || context == null || mc.textRenderer == null) return;
        int cx = context.getScaledWindowWidth() / 2;
        int y = context.getScaledWindowHeight() / 2 + 14;
        int color = baseColor();
        int w1 = mc.textRenderer.getWidth(line1);
        context.drawTextWithShadow(mc.textRenderer, line1, cx - w1 / 2, y, color);
        if (!line2.isEmpty()) {
            int c2 = targetLethal ? 0xFF000000 | lethalColor.getValue().getRGB() : 0xFFFFFFFF;
            int w2 = mc.textRenderer.getWidth(line2);
            context.drawTextWithShadow(mc.textRenderer, line2, cx - w2 / 2, y + mc.textRenderer.fontHeight + 2, c2);
        }
    }
}
