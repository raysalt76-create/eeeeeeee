package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Equipment strip with item icons, durability, and an optional offhand slot. */
public final class ArmorHudElement extends HUDElement {
    private static final float PAD_X = 8f;
    private static final float HEADER_HEIGHT = 18f;

    private final ModeSetting layout = new ModeSetting("Layout", "Horizontal", "Horizontal", "Vertical");
    private final BooleanSetting showDurability = new BooleanSetting("Durability", true);
    private final BooleanSetting showPercentage = new BooleanSetting("Percentage", true);
    private final BooleanSetting showOffhand = new BooleanSetting("Offhand", true);
    private final BooleanSetting showEmptySlots = new BooleanSetting("Empty Slots", false);
    private final SliderSetting iconScale = new SliderSetting("Icon Scale", 1.0, 0.75, 1.5, 2);
    private final SliderSetting spacing = new SliderSetting("Spacing", 5.0, 2.0, 10.0, 0);

    public ArmorHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
        setWidth(144);
        setHeight(56);
    }

    public ModeSetting getLayoutSetting() { return layout; }
    public BooleanSetting getShowDurability() { return showDurability; }
    public BooleanSetting getShowPercentage() { return showPercentage; }
    public BooleanSetting getShowOffhand() { return showOffhand; }
    public BooleanSetting getShowEmptySlots() { return showEmptySlots; }
    public float getIconScaleValue() { return (float) iconScale.getValue(); }
    public float getSpacingValue() { return (float) spacing.getValue(); }
    public boolean isHorizontal() { return "Horizontal".equalsIgnoreCase(layout.getCurrentMode()); }

    @Override
    protected List<Setting> elementSettings() {
        return List.of(layout, showDurability, showPercentage, showOffhand, showEmptySlots, iconScale, spacing);
    }

    public ArmorLayout getArmorLayout() {
        List<ArmorSlot> slots = collectSlots();
        float scale = getIconScaleValue();
        float cell = 24f * scale;
        float gap = getSpacingValue();
        float detailHeight = (showDurability.isEnabled() ? 4f : 0f)
                + (showPercentage.isEnabled() ? 9f : 0f);
        List<SlotPlacement> placements = new ArrayList<>(slots.size());
        float width;
        float height;

        if (isHorizontal()) {
            float bodyWidth = slots.isEmpty() ? cell : slots.size() * cell + (slots.size() - 1) * gap;
            width = Math.max(100f, PAD_X * 2 + bodyWidth);
            height = HEADER_HEIGHT + 7f + cell + detailHeight + 7f;
            float startX = (width - bodyWidth) * 0.5f;
            float iconSize = 16f * scale;
            for (int i = 0; i < slots.size(); i++) {
                float cellX = startX + i * (cell + gap);
                placements.add(new SlotPlacement(slots.get(i), cellX + (cell - iconSize) * 0.5f,
                        HEADER_HEIGHT + 7f + (cell - iconSize) * 0.5f,
                        cellX, HEADER_HEIGHT + 7f, cell, cell, cellX,
                        HEADER_HEIGHT + 7f + cell + 1f, cell));
            }
        } else {
            float detailWidth = (showDurability.isEnabled() || showPercentage.isEnabled()) ? 48f : 0f;
            width = PAD_X * 2 + cell + (detailWidth > 0 ? gap + detailWidth : 0);
            height = HEADER_HEIGHT + 7f + (slots.isEmpty() ? cell : slots.size() * cell + (slots.size() - 1) * gap) + 7f;
            float iconSize = 16f * scale;
            for (int i = 0; i < slots.size(); i++) {
                float cellY = HEADER_HEIGHT + 7f + i * (cell + gap);
                placements.add(new SlotPlacement(slots.get(i), PAD_X + (cell - iconSize) * 0.5f,
                        cellY + (cell - iconSize) * 0.5f, PAD_X, cellY, cell, cell,
                        PAD_X + cell + gap, cellY + 2f, detailWidth));
            }
        }

        setWidth(width);
        setHeight(height);
        return new ArmorLayout(width, height, cell, List.copyOf(placements));
    }

    /** Draw actual Minecraft item models over the GPU-drawn panel. */
    public void renderItemIcons(DrawContext context) {
        ArmorLayout armorLayout = getArmorLayout();
        float scale = getIconScaleValue();
        // The GPU panel is scaled about the widget's top-left by the widget Scale; follow it.
        float widgetScale = (float) getScale();
        float ox = (float) getX(), oy = (float) getY();
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        try {
            if (Math.abs(widgetScale - 1f) > 1e-3f) {
                matrices.translate(ox, oy);
                matrices.scale(widgetScale, widgetScale);
                matrices.translate(-ox, -oy);
            }
            for (SlotPlacement slot : armorLayout.slots()) {
                if (slot.item().stack().isEmpty()) continue;
                float x = ox + slot.iconX();
                float y = oy + slot.iconY();
                matrices.pushMatrix();
                try {
                    matrices.translate(x, y);
                    matrices.scale(scale, scale);
                    matrices.translate(-x, -y);
                    context.drawItem(slot.item().stack(), Math.round(x), Math.round(y));
                } finally {
                    matrices.popMatrix();
                }
            }
        } finally {
            matrices.popMatrix();
        }
    }

    private List<ArmorSlot> collectSlots() {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.player == null) return List.of();
        List<ArmorSlot> slots = new ArrayList<>(5);
        addSlot(slots, "H", client.player.getEquippedStack(EquipmentSlot.HEAD));
        addSlot(slots, "C", client.player.getEquippedStack(EquipmentSlot.CHEST));
        addSlot(slots, "L", client.player.getEquippedStack(EquipmentSlot.LEGS));
        addSlot(slots, "B", client.player.getEquippedStack(EquipmentSlot.FEET));
        if (showOffhand.isEnabled()) addSlot(slots, "O", client.player.getOffHandStack());
        return slots;
    }

    private void addSlot(List<ArmorSlot> slots, String label, ItemStack stack) {
        if (showEmptySlots.isEnabled() || !stack.isEmpty()) slots.add(new ArmorSlot(label, stack));
    }

    @Override
    public void render(DrawContext context, float delta) {
        renderItemIcons(context);
    }

    public record ArmorSlot(String label, ItemStack stack) { }
    public record SlotPlacement(ArmorSlot item, float iconX, float iconY, float cellX, float cellY,
                                float cellWidth, float cellHeight, float detailX, float detailY,
                                float detailWidth) { }
    public record ArmorLayout(float width, float height, float cellSize, List<SlotPlacement> slots) { }
}
