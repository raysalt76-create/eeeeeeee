package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.StringSetting;
import interstellarTikiTiks.apexx.client.module.hooks.ItemGlowProvider;
import interstellarTikiTiks.apexx.client.render.msdf.Msdf;
import interstellarTikiTiks.apexx.client.render.msdf.MsdfFont;
import interstellarTikiTiks.apexx.client.render.msdf.WorldProjector;
import interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator;
import interstellarTikiTiks.apexx.client.util.render.RibbonRenderer;
import interstellarTikiTiks.apexx.client.util.render.ThemeColors;
import interstellarTikiTiks.apexx.client.util.render.TrailMath;
import interstellarTikiTiks.apexx.client.util.render.types.RenderTypes;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ItemESP - the one dropped-item highlighter (the former Item Glow is merged in). Every item that passes the
 * filters can get a see-through box, a tracer, a vanilla-style glow outline, a rotating ring highlight and a
 * name label. Valuables (netherite, diamond, totems, elytra, enchanted and other rare loot) can be shown on
 * their own and coloured per category or in one valuable colour. Purely visual: nothing is sent to the server.
 */
public final class ItemESP extends Module implements ItemGlowProvider {

    private static final String FILTER_SECTION = "Filter";
    private static final String EFFECTS = "Effects";
    private static final String COLOR_SECTION = "Color";
    private static final String BLACKLIST = "Blacklist";
    private static final String WHITELIST = "Whitelist";
    private static final String ALL = "All";
    private static final String VALUABLES = "Valuables";
    private static final String PER_CATEGORY = "Per Category";
    private static final int MAX_ITEMS = 512;

    enum Kind { TOTEM, ELYTRA, NETHERITE, DIAMOND, RARE, ENCHANTED, COMMON }

    // ---- Filter ----
    private final SliderSetting range = new SliderSetting("Range", 64.0, 8.0, 256.0, 0)
            .setDescription("Blocks. Items further away than this are not drawn.").setSection(FILTER_SECTION);
    private final ModeSetting show = new ModeSetting("Show", ALL, ALL, VALUABLES)
            .setDescription("All marks every dropped item; Valuables only marks the categories switched on below.")
            .setSection(FILTER_SECTION);
    private final BooleanSetting netherite = new BooleanSetting("Netherite", true)
            .setDescription("Netherite gear, ingots, scrap, upgrade templates and ancient debris.")
            .setDependsOn("Show:Valuables").setSection(FILTER_SECTION);
    private final BooleanSetting diamond = new BooleanSetting("Diamond", true)
            .setDescription("Diamonds and diamond gear.").setDependsOn("Show:Valuables").setSection(FILTER_SECTION);
    private final BooleanSetting totem = new BooleanSetting("Totem", true)
            .setDescription("Totems of Undying.").setDependsOn("Show:Valuables").setSection(FILTER_SECTION);
    private final BooleanSetting elytra = new BooleanSetting("Elytra", true)
            .setDescription("Elytra.").setDependsOn("Show:Valuables").setSection(FILTER_SECTION);
    private final BooleanSetting enchanted = new BooleanSetting("Enchanted", true)
            .setDescription("Any enchanted item or enchanted book.").setDependsOn("Show:Valuables").setSection(FILTER_SECTION);
    private final BooleanSetting rare = new BooleanSetting("Rare", true)
            .setDescription("Golden apples, maces, heavy cores, tridents, shulker boxes, nether stars, beacons, end crystals, dragon eggs, emeralds, respawn anchors, ender pearls and experience bottles.")
            .setDependsOn("Show:Valuables").setSection(FILTER_SECTION);
    private final ModeSetting filter = new ModeSetting("Filter", BLACKLIST, WHITELIST)
            .setDescription("Blacklist: show every item except those listed in Items. Whitelist: show only the items listed in Items.")
            .setSection(FILTER_SECTION);
    private final StringSetting items = new StringSetting("Items", "")
            .setDescription("Comma separated item names for the filter, e.g. cobblestone,dirt,rotten_flesh.")
            .setSection(FILTER_SECTION);
    private final BooleanSetting merge = new BooleanSetting("Skip Stacked Duplicates", false)
            .setDescription("Marks only one item per block when several lie on the same block.").setSection(FILTER_SECTION);

    // ---- Effects ----
    private final BooleanSetting box = new BooleanSetting("Box", true)
            .setDescription("Draws a see-through box around each item.").setSection(EFFECTS);
    private final ModeSetting style = new ModeSetting("Style", SmpRender.BOTH, SmpRender.OUTLINE, SmpRender.FILLED)
            .setDescription("How each box is drawn: Both = outline plus translucent fill, Outline = edges only, Filled = solid translucent box.")
            .setDependsOn("Box").setSection(EFFECTS);
    private final SliderSetting fillOpacity = new SliderSetting("Fill Opacity", 0.2, 0.05, 0.8, 2)
            .setDescription("How solid the filled part of the box is.").setSection(EFFECTS);
    private final BooleanSetting tracers = new BooleanSetting("Tracers", false)
            .setDescription("Draws a line from your view to each item.").setSection(EFFECTS);
    private final BooleanSetting glowOutline = new BooleanSetting("Glow Outline", false)
            .setDescription("Draws a glowing outline around the item itself, visible through walls like the Glowing effect.")
            .setSection(EFFECTS);
    private final BooleanSetting highlight = new BooleanSetting("Highlight", false)
            .setDescription("A slowly rotating ring under the item with a gently bobbing halo.").setSection(EFFECTS);
    private final BooleanSetting labels = new BooleanSetting("Labels", false)
            .setDescription("Shows the item name and stack count above each marked item.").setSection(EFFECTS);

    // ---- Color ----
    private final ModeSetting colorMode = new ModeSetting("Color", ThemeColors.CUSTOM, ThemeColors.THEME,
            ThemeColors.CUSTOM, ThemeColors.RAINBOW, PER_CATEGORY)
            .setDescription("Theme follows the GUI accent; Custom uses Custom Color; Rainbow cycles the hue; Per Category uses the colours below.")
            .setSection(COLOR_SECTION);
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(255, 179, 72))
            .setDescription("Item colour when Color is Custom.").setDependsOn("Color:Custom").setSection(COLOR_SECTION);
    private final BooleanSetting highlightValuables = new BooleanSetting("Highlight Valuables", true)
            .setDescription("Draws valuables (diamonds, netherite, totems, elytra, golden apples and the like) in Valuable Color.")
            .setDependsOn("!Color:Per Category").setSection(COLOR_SECTION);
    private final ColorSetting valuableColor = new ColorSetting("Valuable Color", new Color(80, 235, 255))
            .setDependsOn("!Color:Per Category,Highlight Valuables").setSection(COLOR_SECTION);
    private final ColorSetting netheriteColor = new ColorSetting("Netherite Color", new Color(155, 123, 255))
            .setDependsOn("Color:Per Category").setSection(COLOR_SECTION);
    private final ColorSetting diamondColor = new ColorSetting("Diamond Color", new Color(78, 230, 240))
            .setDependsOn("Color:Per Category").setSection(COLOR_SECTION);
    private final ColorSetting totemColor = new ColorSetting("Totem Color", new Color(245, 209, 66))
            .setDependsOn("Color:Per Category").setSection(COLOR_SECTION);
    private final ColorSetting elytraColor = new ColorSetting("Elytra Color", new Color(192, 178, 255))
            .setDependsOn("Color:Per Category").setSection(COLOR_SECTION);
    private final ColorSetting enchantedColor = new ColorSetting("Enchanted Color", new Color(224, 112, 255))
            .setDependsOn("Color:Per Category").setSection(COLOR_SECTION);
    private final ColorSetting rareColor = new ColorSetting("Rare Color", new Color(255, 154, 60))
            .setDependsOn("Color:Per Category").setSection(COLOR_SECTION);
    private final ColorSetting commonColor = new ColorSetting("Common Color", new Color(235, 235, 235))
            .setDependsOn("Color:Per Category").setSection(COLOR_SECTION);

    /** Item -> category, by item identity. Enchantment is per stack and checked separately. */
    private final Map<Item, Kind> kindCache = new IdentityHashMap<>();
    /** Entity id -> opaque ARGB of the items marked this tick. */
    private final Int2IntOpenHashMap marked = new Int2IntOpenHashMap();
    private final List<ItemEntity> markedEntities = new ArrayList<>();
    private ClientWorld lastWorld;
    private String parsedSource;
    private Set<String> parsedNames = Set.of();

    public ItemESP() {
        super("ItemESP", "Highlights dropped items within range so loot and deaths are easy to find: see-through boxes, tracers, a glowing outline, a rotating ring and name labels, in any mix. Show every item or only valuables (netherite, diamond, totems, elytra, enchanted and rare loot), colour them per category or give valuables their own colour, and hide junk with a blacklist/whitelist. Visual only: nothing is sent to the server.", Category.RENDER);
        addSetting(range);
        addSetting(show);
        addSetting(netherite);
        addSetting(diamond);
        addSetting(totem);
        addSetting(elytra);
        addSetting(enchanted);
        addSetting(rare);
        addSetting(filter);
        addSetting(items);
        addSetting(merge);
        addSetting(box);
        addSetting(style);
        addSetting(fillOpacity);
        fillOpacity.setVisibility(() -> box.isEnabled() && !style.is(SmpRender.OUTLINE));
        addSetting(tracers);
        addSetting(glowOutline);
        addSetting(highlight);
        addSetting(labels);
        addSetting(colorMode);
        addSetting(customColor);
        addSetting(highlightValuables);
        addSetting(valuableColor);
        addSetting(netheriteColor);
        addSetting(diamondColor);
        addSetting(totemColor);
        addSetting(elytraColor);
        addSetting(enchantedColor);
        addSetting(rareColor);
        addSetting(commonColor);
        marked.defaultReturnValue(0);
    }

    @Override
    public boolean rendersInWorld() {
        return true;
    }

    @Override
    public void onDisable() {
        clear();
    }

    @Override
    protected void onRuntimeStateReleased() {
        clear();
    }

    private void clear() {
        marked.clear();
        markedEntities.clear();
        lastWorld = null;
    }

    // ---- selection (once per tick) ----

    @Override
    public void onTick() {
        marked.clear();
        markedEntities.clear();
        if (mc.player == null || mc.world == null) return;
        if (mc.world != lastWorld) {
            kindCache.clear();
            lastWorld = mc.world;
        }
        double rangeSq = range.getValue() * range.getValue();
        boolean all = ALL.equals(show.getCurrentMode());
        Set<String> names = names();
        boolean whitelist = filter.is(WHITELIST);
        Set<Long> seen = merge.isEnabled() ? new HashSet<>() : null;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity drop) || drop.isRemoved()) continue;
            if (drop.squaredDistanceTo(mc.player) > rangeSq) continue;
            ItemStack stack = drop.getStack();
            if (stack.isEmpty()) continue;
            if (!names.isEmpty() && names.contains(Registries.ITEM.getId(stack.getItem()).getPath()) != whitelist) continue;
            if (names.isEmpty() && whitelist) continue;
            Kind kind = classify(stack);
            if (!all && !wanted(kind)) continue;
            if (seen != null && !seen.add(drop.getBlockPos().asLong())) continue;
            marked.put(drop.getId(), colorFor(kind, markedEntities.size()));
            markedEntities.add(drop);
            if (markedEntities.size() >= MAX_ITEMS) break;
        }
    }

    private Set<String> names() {
        String source = items.getValue();
        if (source == null) source = "";
        if (!source.equals(parsedSource)) {
            parsedSource = source;
            parsedNames = parse(source);
        }
        return parsedNames;
    }

    private boolean wanted(Kind kind) {
        return switch (kind) {
            case TOTEM -> totem.isEnabled();
            case ELYTRA -> elytra.isEnabled();
            case NETHERITE -> netherite.isEnabled();
            case DIAMOND -> diamond.isEnabled();
            case RARE -> rare.isEnabled();
            case ENCHANTED -> enchanted.isEnabled();
            case COMMON -> false;
        };
    }

    private int colorFor(Kind kind, int index) {
        String mode = colorMode.getCurrentMode();
        if (PER_CATEGORY.equals(mode)) {
            Color c = switch (kind) {
                case TOTEM -> totemColor.getValue();
                case ELYTRA -> elytraColor.getValue();
                case NETHERITE -> netheriteColor.getValue();
                case DIAMOND -> diamondColor.getValue();
                case RARE -> rareColor.getValue();
                case ENCHANTED -> enchantedColor.getValue();
                case COMMON -> commonColor.getValue();
            };
            return ThemeColors.opaque(c);
        }
        if (highlightValuables.isEnabled() && kind != Kind.COMMON) return ThemeColors.opaque(valuableColor.getValue());
        return ThemeColors.resolve(mode, customColor.getValue(), index * 0.07f);
    }

    private Kind classify(ItemStack stack) {
        Kind base = kindCache.computeIfAbsent(stack.getItem(), ItemESP::classifyItem);
        if (base == Kind.COMMON && stack.hasGlint()) return Kind.ENCHANTED;
        return base;
    }

    static Kind classifyItem(Item item) {
        if (item == Items.TOTEM_OF_UNDYING) return Kind.TOTEM;
        if (item == Items.ELYTRA) return Kind.ELYTRA;
        String path = Registries.ITEM.getId(item).getPath();
        return classifyPath(path, item == Items.ENCHANTED_BOOK);
    }

    /** Category from an item registry path; package-private for tests. */
    static Kind classifyPath(String path, boolean enchantedBook) {
        if (path == null) return Kind.COMMON;
        if (path.equals("totem_of_undying")) return Kind.TOTEM;
        if (path.equals("elytra")) return Kind.ELYTRA;
        if (path.contains("netherite") || path.equals("ancient_debris")) return Kind.NETHERITE;
        if (path.contains("diamond") && !path.equals("diamond_horse_armor")) return Kind.DIAMOND;
        if (enchantedBook || path.equals("enchanted_book")) return Kind.ENCHANTED;
        if (path.equals("enchanted_golden_apple") || path.equals("golden_apple") || path.equals("mace")
                || path.equals("heavy_core") || path.equals("trident") || path.endsWith("shulker_box")
                || path.equals("nether_star") || path.equals("beacon") || path.equals("end_crystal")
                || path.equals("dragon_egg") || path.equals("emerald") || path.equals("emerald_block")
                || path.equals("respawn_anchor") || path.equals("ender_pearl") || path.equals("experience_bottle")) {
            return Kind.RARE;
        }
        return Kind.COMMON;
    }

    private static Set<String> parse(String list) {
        Set<String> out = new HashSet<>();
        if (list == null) return out;
        for (String part : list.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    // ---- glow outline (vanilla outline pass via ItemEntityRendererGlowMixin) ----

    @Override
    public int itemGlowColor(ItemEntity item) {
        if (!glowOutline.isEnabled() || item == null) return 0;
        int argb = marked.get(item.getId());
        if (argb == 0) return 0;
        int rgb = argb & 0x00FFFFFF;
        return rgb == 0 ? 0x010101 : rgb;
    }

    // ---- world: boxes, tracers, ring highlight ----

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (markedEntities.isEmpty() || mc.player == null || mc.world == null) return;
        boolean drawBox = box.isEnabled();
        boolean drawTracers = tracers.isEnabled();
        boolean drawRing = highlight.isEnabled();
        if (!drawBox && !drawTracers && !drawRing) return;
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        double time = ThemeColors.seconds();
        float maxRange = (float) range.getValue();

        try (BufferAllocator allocator = PooledBufferAllocator.acquire(16384)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            if (drawBox || drawTracers) {
                for (int i = 0; i < markedEntities.size(); i++) {
                    ItemEntity item = markedEntities.get(i);
                    if (item.isRemoved()) continue;
                    int argb = marked.get(item.getId());
                    if (argb == 0) continue;
                    Color c = new Color(argb & 0x00FFFFFF);
                    Vec3d at = item.getLerpedPos(tickDelta);
                    Box b = item.getBoundingBox().offset(at.x - item.getX(), at.y - item.getY(), at.z - item.getZ())
                            .expand(0.1);
                    if (drawBox) {
                        SmpRender.box(matrices, immediate, b, cam, c, style.getCurrentMode(), (float) fillOpacity.getValue());
                    }
                    if (drawTracers) SmpRender.tracer(matrices, immediate, cam, b.getCenter(), c, tickDelta);
                }
            }
            if (drawRing) {
                VertexConsumer buffer = immediate.getBuffer(RenderTypes.TRIANGLES);
                Matrix4f matrix = matrices.peek().getPositionMatrix();
                for (int i = 0; i < markedEntities.size(); i++) {
                    ItemEntity item = markedEntities.get(i);
                    if (item.isRemoved()) continue;
                    int color = marked.get(item.getId());
                    if (color == 0) continue;
                    Vec3d pos = item.getLerpedPos(tickDelta);
                    float x = (float) (pos.x - cam.x);
                    float y = (float) (pos.y - cam.y);
                    float z = (float) (pos.z - cam.z);
                    float dist = (float) Math.sqrt(x * x + y * y + z * z);
                    float alpha = 1.0f - TrailMath.smoothstep(maxRange * 0.7f, maxRange, dist);
                    if (alpha <= 0.01f) continue;
                    float phase = (item.getId() % 97) * 0.37f;
                    float spin = (float) (time * 1.1) + phase;
                    float bob = 0.5f + 0.5f * (float) Math.sin(time * 2.0 + phase);

                    RibbonRenderer.disc(buffer, matrix, x, y + 0.02f, z, 0.46f, 24,
                            TrailMath.withAlpha(color, 0.20f * alpha), TrailMath.withAlpha(color, 0.0f));
                    RibbonRenderer.dashedRing(buffer, matrix, x, y + 0.03f, z, 0.38f, 0.035f, 48, 4, 0.62f, spin,
                            TrailMath.withAlpha(color, 0.85f * alpha), 0.25f);
                    RibbonRenderer.ring(buffer, matrix, x, y + 0.18f + 0.22f * bob, z, 0.24f + 0.04f * bob, 0.05f, 32,
                            TrailMath.withAlpha(TrailMath.brighten(color, 0.25f), (0.45f - 0.2f * bob) * alpha), 0.0f);
                }
            }
            immediate.draw();
        }
    }

    // ---- HUD: name labels ----

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (!isEnabled() || !labels.isEnabled() || markedEntities.isEmpty() || mc.player == null || mc.world == null) return;
        if (!Msdf.begin()) return;
        try {
            MsdfFont font = Msdf.semiBold();
            if (font == null) return;
            int screenW = Msdf.width();
            int screenH = Msdf.height();
            Matrix4f viewProj = WorldProjector.viewProjection(tickDelta);
            for (int i = 0; i < markedEntities.size(); i++) {
                ItemEntity item = markedEntities.get(i);
                if (item.isRemoved()) continue;
                int argb = marked.get(item.getId());
                if (argb == 0) continue;
                double ix = MathHelper.lerp(tickDelta, item.lastRenderX, item.getX());
                double iy = MathHelper.lerp(tickDelta, item.lastRenderY, item.getY());
                double iz = MathHelper.lerp(tickDelta, item.lastRenderZ, item.getZ());
                Box b = item.getBoundingBox().offset(ix - item.getX(), iy - item.getY(), iz - item.getZ());
                WorldProjector.Projected proj = WorldProjector.project(viewProj, b, screenW, screenH);
                if (!proj.visible()) continue;
                drawLabel(font, item.getStack(), proj.centerX(), proj.minY() - 14.0f, argb);
            }
        } finally {
            Msdf.end();
        }
    }

    private static void drawLabel(MsdfFont font, ItemStack stack, float centerX, float topY, int accentArgb) {
        if (stack == null || stack.isEmpty()) return;
        String label = stack.getName().getString();
        if (stack.getCount() > 1) label = label + " x" + stack.getCount();
        float fontSize = 11.5f;
        float textW = Msdf.width(font, label, fontSize);
        float padX = 5.0f;
        float padY = 2.5f;
        float boxX = centerX - textW * 0.5f - padX;
        float boxY = topY - padY;
        Msdf.rect(boxX, boxY, textW + padX * 2.0f, fontSize + padY * 2.0f, 3.5f, 0xB80A0D14);
        Msdf.rect(boxX, boxY, textW + padX * 2.0f, 1.2f, 1.0f, (210 << 24) | (accentArgb & 0x00FFFFFF));
        Msdf.textWithShadow(font, label, centerX - textW * 0.5f, topY, fontSize, 0xFFF2F6FF);
    }
}
