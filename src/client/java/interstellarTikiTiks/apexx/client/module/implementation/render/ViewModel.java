package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.HeldItemAnimationProvider;
import interstellarTikiTiks.apexx.client.module.hooks.ViewTransformProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/**
 * First-person held-item placement: per-hand offset, scale and rotation, plus swing strength and
 * instant re-equip and the local swing duration (formerly the separate Swing Speed module). Purely
 * visual and local; the swing duration is only taken over when it differs from vanilla's 6 ticks.
 */
public final class ViewModel extends Module implements ViewTransformProvider, HeldItemAnimationProvider {
    private static final String MAIN = "Main Hand";
    private static final String OFF = "Off Hand";
    private static final String SWING = "Swing";
    private static final double VANILLA_SWING_TICKS = 6.0D;

    private final HandSettings main = new HandSettings("", MAIN);
    private final HandSettings off = new HandSettings("Off ", OFF);
    private final BooleanSetting mirrorMain = new BooleanSetting("Same As Main Hand", false)
            .setDescription("Uses the main-hand values for the off hand too, mirrored to the other side of the screen.")
            .setSection(OFF);

    private final SliderSetting swingStrength = new SliderSetting("Swing Strength", 100, 0, 200, 0)
            .setDescription("How far the item rotates during a swing, in percent of vanilla. 0 removes the swing "
                    + "rotation (the small forward push stays); 100 is vanilla. Only the animation changes, never "
                    + "your attack speed.")
            .setSection(SWING);
    private final BooleanSetting instantEquip = new BooleanSetting("Instant Equip", false)
            .setDescription("Skips the lower-and-raise animation when you switch hotbar slots, so the new item is "
                    + "shown immediately.")
            .setSection(SWING);
    private final SliderSetting swingDuration = new SliderSetting("Swing Duration (ticks)", VANILLA_SWING_TICKS, 1.0, 20.0, 0)
            .setDescription("How many ticks your first-person arm swing animation lasts. Vanilla is 6; lower swings "
                    + "faster, higher slower. Only the animation changes, never your attack speed or cooldown.")
            .setSection(SWING);

    public ViewModel() {
        super("ViewModel", "Moves, scales and rotates the items you hold in first person, separately for each hand, "
                + "and can tone down or speed up the swing animation or skip the re-equip animation. Visible only to you; "
                + "attack speed and cooldown are unchanged.",
                Category.RENDER);
        main.register();
        addSetting(mirrorMain);
        off.register();
        addSetting(swingStrength);
        addSetting(instantEquip);
        addSetting(swingDuration);
        off.setVisibility(() -> !mirrorMain.isEnabled());
    }

    @Override
    public boolean rendersInWorld() {
        return false;
    }

    // ---- ViewTransformProvider --------------------------------------------------------------------

    @Override
    public Target getApplyTarget() {
        return Target.BOTH;
    }

    @Override
    public void applyTransformations(MatrixStack matrices) {
        applyTransformations(matrices, Hand.MAIN_HAND);
    }

    @Override
    public void applyTransformations(MatrixStack matrices, Hand hand) {
        if (matrices == null || mc.player == null) return;
        HandSettings s = hand == Hand.OFF_HAND && !mirrorMain.isEnabled() ? off : main;
        Arm mainArm = mc.player.getMainArm();
        Arm arm = hand == Hand.MAIN_HAND ? mainArm : mainArm.getOpposite();
        // Positive X / yaw / roll point outward for both arms, so one set of values mirrors naturally.
        float side = arm == Arm.RIGHT ? 1.0f : -1.0f;

        float x = (float) s.x.getValue() * side;
        float y = (float) s.y.getValue();
        float z = (float) s.z.getValue();
        if (x != 0.0f || y != 0.0f || z != 0.0f) matrices.translate(x, y, z);

        float rx = (float) s.rotX.getValue();
        float ry = (float) s.rotY.getValue() * side;
        float rz = (float) s.rotZ.getValue() * side;
        if (rx != 0.0f) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(rx));
        if (ry != 0.0f) matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(ry));
        if (rz != 0.0f) matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rz));

        float scale = (float) s.scale.getValue();
        if (Float.isFinite(scale) && scale > 0.0f && scale != 1.0f) matrices.scale(scale, scale, scale);
    }

    // ---- HeldItemAnimationProvider ------------------------------------------------------------------

    @Override
    public boolean controlsSwingDuration() {
        return Math.round(swingDuration.getValue()) != Math.round(VANILLA_SWING_TICKS);
    }

    /** Speed factor for LivingEntityMixin (duration = 6 / speed); vanilla 1.0 while the duration is 6 ticks. */
    @Override
    public double getSwingSpeed() {
        double ticks = Math.max(1.0D, Math.round(swingDuration.getValue()));
        return VANILLA_SWING_TICKS / ticks;
    }

    @Override
    public boolean isInstantEquipEnabled() {
        return instantEquip.isEnabled();
    }

    @Override
    public boolean onRenderFirstPerson(MatrixStack matrices, float swingProgress, ItemStack itemStack, Hand hand) {
        return false;
    }

    /** Vanilla's swing rotation with its amplitude scaled; returns false at 100% so vanilla runs untouched. */
    @Override
    public boolean applyCustomSwingOffset(MatrixStack matrices, Arm arm, float swingProgress) {
        double strength = swingStrength.getValue();
        if (Math.abs(strength - 100.0) < 0.5 || matrices == null || arm == null) return false;
        float amp = (float) (strength / 100.0);
        int i = arm == Arm.RIGHT ? 1 : -1;
        float f = MathHelper.sin(swingProgress * swingProgress * (float) Math.PI);
        float g = MathHelper.sin(MathHelper.sqrt(swingProgress) * (float) Math.PI);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(i * (45.0f + f * -20.0f * amp)));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(i * g * -20.0f * amp));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(g * -80.0f * amp));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(i * -45.0f));
        return true;
    }

    /** The offset/rotation/scale sliders for one hand. */
    private final class HandSettings {
        final SliderSetting x;
        final SliderSetting y;
        final SliderSetting z;
        final SliderSetting scale;
        final SliderSetting rotX;
        final SliderSetting rotY;
        final SliderSetting rotZ;

        HandSettings(String prefix, String section) {
            String which = section.toLowerCase(java.util.Locale.ROOT);
            x = new SliderSetting(prefix + "X", 0.0, -1.0, 1.0, 2)
                    .setDescription("Moves the " + which + " item sideways: positive pushes it outward, negative toward the centre of the screen.")
                    .setSection(section);
            y = new SliderSetting(prefix + "Y", 0.0, -1.0, 1.0, 2)
                    .setDescription("Moves the " + which + " item up (positive) or down (negative).")
                    .setSection(section);
            z = new SliderSetting(prefix + "Z", 0.0, -1.0, 1.0, 2)
                    .setDescription("Moves the " + which + " item away from you (negative) or closer (positive).")
                    .setSection(section);
            scale = new SliderSetting(prefix + "Scale", 1.0, 0.1, 2.0, 2)
                    .setDescription("Size of the " + which + " item; 1 is vanilla.")
                    .setSection(section);
            rotX = new SliderSetting(prefix + "Rotate X", 0, -180, 180, 0)
                    .setDescription("Tilts the " + which + " item forward or back, in degrees.")
                    .setSection(section);
            rotY = new SliderSetting(prefix + "Rotate Y", 0, -180, 180, 0)
                    .setDescription("Turns the " + which + " item left or right, in degrees (mirrored for the other arm).")
                    .setSection(section);
            rotZ = new SliderSetting(prefix + "Rotate Z", 0, -180, 180, 0)
                    .setDescription("Rolls the " + which + " item sideways, in degrees (mirrored for the other arm).")
                    .setSection(section);
        }

        void register() {
            addSetting(x);
            addSetting(y);
            addSetting(z);
            addSetting(scale);
            addSetting(rotX);
            addSetting(rotY);
            addSetting(rotZ);
        }

        void setVisibility(java.util.function.Supplier<Boolean> visible) {
            x.setVisibility(visible);
            y.setVisibility(visible);
            z.setVisibility(visible);
            scale.setVisibility(visible);
            rotX.setVisibility(visible);
            rotY.setVisibility(visible);
            rotZ.setVisibility(visible);
        }
    }
}
