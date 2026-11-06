package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.mixin.SimpleOptionAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.StringSetting;
import interstellarTikiTiks.apexx.client.util.XrayState;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Xray (SMP) - shows ores and chosen blocks through the terrain by leaving every other block out of
 * the chunk mesh. Ported from LiquidBounce's XRay, including the transparent background (Background
 * Opacity); its exposed-only option is not carried over, and the block list is switch groups plus a free list.
 *
 * <p>Covered chunk builders: vanilla ({@code BlockXrayMixin}, {@code BlockRenderManagerXrayMixin}) and
 * Sodium 0.8.6 ({@code SodiumXray*Mixin}, so Iris on Sodium too). {@code ChunkOcclusionXrayMixin} stops
 * whole sections behind rock from being culled. Any other chunk renderer is not covered.</p>
 */
public final class Xray extends Module {

    private final Map<BooleanSetting, Block[]> blockGroups = new LinkedHashMap<>();
    private final BooleanSetting fullBright = new BooleanSetting("Full Bright", true)
            .setDescription("Lights the ores up in dark caves by raising gamma while Xray is on.");
    private final SliderSetting backgroundOpacity = new SliderSetting("Background Opacity", 0.0, 0.0, 255.0, 0)
            .setDescription("0 hides every other block. Above 0 the other blocks stay in the world, drawn translucent at this opacity (255 is solid).");
    private final StringSetting custom = new StringSetting("Custom Blocks", "")
            .setDescription("Comma separated block names to also show, e.g. gilded_blackstone,reinforced_deepslate.");

    private volatile Set<Block> visible = Set.of();
    private String signature = "";
    private double originalGamma = 1.0;
    private boolean gammaCaptured;

    public Xray() {
        super("Xray", "Hides ordinary terrain so ores and the blocks you choose show through the ground, with optional full brightness for dark caves. Pick ore groups or add custom blocks. Rendering only, but servers with anti-xray can still notice mining that goes straight to ores.", Category.RENDER);
        group("Coal", true, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.COAL_BLOCK);
        group("Iron", true, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.RAW_IRON_BLOCK, Blocks.IRON_BLOCK);
        group("Copper", false, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.RAW_COPPER_BLOCK);
        group("Gold", true, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE,
                Blocks.RAW_GOLD_BLOCK, Blocks.GOLD_BLOCK);
        group("Redstone", true, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.REDSTONE_BLOCK);
        group("Lapis", true, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.LAPIS_BLOCK);
        group("Diamond", true, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.DIAMOND_BLOCK);
        group("Emerald", true, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.EMERALD_BLOCK);
        group("Quartz", false, Blocks.NETHER_QUARTZ_ORE, Blocks.QUARTZ_BLOCK);
        group("Ancient Debris", true, Blocks.ANCIENT_DEBRIS, Blocks.NETHERITE_BLOCK);
        group("Spawners", true, Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.VAULT);
        group("Storage", true, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.BARREL,
                Blocks.HOPPER, Blocks.DROPPER, Blocks.DISPENSER);
        group("Stations", false, Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
                Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL, Blocks.BEACON,
                Blocks.BREWING_STAND, Blocks.SMITHING_TABLE);
        group("Portals", false, Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_PORTAL_FRAME,
                Blocks.END_GATEWAY);
        group("Command Blocks", false, Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK,
                Blocks.REPEATING_COMMAND_BLOCK);
        group("TNT & Hazards", false, Blocks.TNT, Blocks.LAVA, Blocks.FIRE, Blocks.MAGMA_BLOCK);

        for (BooleanSetting setting : blockGroups.keySet()) addSetting(setting);
        addSetting(custom);
        addSetting(backgroundOpacity);
        addSetting(fullBright);
    }

    private void group(String name, boolean on, Block... blocks) {
        blockGroups.put(new BooleanSetting(name, on).setDescription("Show " + name.toLowerCase(Locale.ROOT)
                + " blocks."), blocks);
    }

    @Override
    public void onEnable() {
        signature = "";
        rebuild();
        XrayState.publish(true, visible, (int) backgroundOpacity.getValue());
        reloadWorld();
        if (mc.options != null) {
            originalGamma = mc.options.getGamma().getValue();
            gammaCaptured = true;
        }
    }

    @Override
    public void onDisable() {
        XrayState.publish(false, Set.of(), 0);
        if (mc.options != null && gammaCaptured) setGamma(originalGamma);
        gammaCaptured = false;
        reloadWorld();
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.options == null) return;
        if (rebuild()) reloadWorld();
        if (fullBright.isEnabled()) {
            if (!gammaCaptured) {
                originalGamma = mc.options.getGamma().getValue();
                gammaCaptured = true;
            }
            if (mc.options.getGamma().getValue() < 15.0) setGamma(16.0);
        } else if (gammaCaptured) {
            setGamma(originalGamma);
            gammaCaptured = false;
        }
    }

    /** Recomputes the visible set when a switch or the free list changed. True when it did. */
    private boolean rebuild() {
        StringBuilder sig = new StringBuilder();
        for (BooleanSetting setting : blockGroups.keySet()) sig.append(setting.isEnabled() ? '1' : '0');
        sig.append('|').append(custom.getValue() == null ? "" : custom.getValue());
        sig.append('|').append((int) backgroundOpacity.getValue());
        String now = sig.toString();
        if (now.equals(signature)) return false;
        signature = now;

        Set<Block> set = new HashSet<>();
        for (Map.Entry<BooleanSetting, Block[]> entry : blockGroups.entrySet()) {
            if (!entry.getKey().isEnabled()) continue;
            for (Block block : entry.getValue()) set.add(block);
        }
        if (custom.getValue() != null) {
            for (String part : custom.getValue().split(",")) {
                String name = part.trim().toLowerCase(Locale.ROOT);
                if (name.isEmpty()) continue;
                Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
                if (id != null && Registries.BLOCK.containsId(id)) set.add(Registries.BLOCK.get(id));
            }
        }
        // Shulker boxes come in seventeen blocks: naming shulker_box in the free list shows all of them.
        if (set.contains(Blocks.SHULKER_BOX)) {
            for (RegistryEntry<Block> entry : Registries.BLOCK.iterateEntries(BlockTags.SHULKER_BOXES)) {
                set.add(entry.value());
            }
        }
        visible = set;
        XrayState.publish(isEnabled(), set, (int) backgroundOpacity.getValue());
        return true;
    }

    private void setGamma(double gamma) {
        SimpleOption<Double> option = mc.options.getGamma();
        if (option != null) ((SimpleOptionAccessor) (Object) option).forceSetValue(gamma);
    }

    private void reloadWorld() {
        if (mc.worldRenderer != null && mc.world != null) mc.worldRenderer.reload();
    }
}
