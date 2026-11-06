package interstellarTikiTiks.apexx.client.hud;

import java.util.ArrayList;
import java.util.List;

public class HUDManager {

    private static HUDManager instance;
    private final List<HUDElement> elements = new ArrayList<>();

    private static double lastHitReach = 0.0;
    private static Object lastWorld = null;

    private HUDManager() {
        FPSHudElement fps = new FPSHudElement("FPS", 16, 16);
        fps.setEnabled(true);
        elements.add(fps);

        CoordinatesHudElement coordinates = new CoordinatesHudElement("Coordinates", 16, 48);
        coordinates.setEnabled(true);
        elements.add(coordinates);

        TargetHudElement targetHud = new TargetHudElement("Target HUD", 0, 0);
        targetHud.setEnabled(false);
        elements.add(targetHud);

        ReachHudElement reach = new ReachHudElement("Last Reach", 290, 16);
        reach.setEnabled(false);
        elements.add(reach);

        EffectHudElement effects = new EffectHudElement("Effect HUD", 290, 56);
        effects.setEnabled(false);
        elements.add(effects);

        KeystrokeHudElement keys = new KeystrokeHudElement("Keystrokes", 290, 104);
        keys.setEnabled(false);
        elements.add(keys);

        elements.add(new GpuHudElement("Bound Modules", GpuHudElement.Kind.KEYBINDS, 16, 216, false));
        // Negative X is a persisted "right edge" anchor until the player drags this widget.
        elements.add(new GpuHudElement("Array List", GpuHudElement.Kind.ARRAY_LIST, -1, 16, true));

        BrandingHudElement watermark = new BrandingHudElement("Watermark", 16, 80);
        watermark.setEnabled(false);
        elements.add(watermark);
        // No combined "Stats" widget: FPS, Ping & TPS and Server Info each own their datum.

        ArmorHudElement armor = new ArmorHudElement("Armor", 16, 420);
        armor.setEnabled(false);
        elements.add(armor);

        CpsHudElement cps = new CpsHudElement("CPS", 420, 16);
        cps.setEnabled(false);
        elements.add(cps);

        // Draws only while AutoBuild has a schematic loaded and its Materials HUD is on.
        elements.add(new SchematicHudElement("Schematic Materials", 420, 56));

        // Optional GPU widgets; all start disabled (turned on from the HUD editor).
        elements.add(new PingTpsHudElement("Ping & TPS", 600, 16));
        elements.add(new ServerInfoHudElement("Server Info", 600, 110));
        elements.add(new SessionStatsHudElement("Session Stats", 600, 190));
        elements.add(new SpeedHudElement("Speed", 420, 96));
        elements.add(new CompassHudElement("Compass", 380, 6));
        elements.add(new ClockHudElement("Clock", 420, 136));
        elements.add(new RadarHudElement("Radar", 16, 270));
        elements.add(new NotificationsHudElement("Notifications", 600, 270));

        // Not a widget: holds the HUD-wide theme settings so they persist with the HUD config.
        elements.add(style);

        // Construction values are the "Reset widget" / "Reset layout" targets in the HUD editor.
        for (HUDElement element : elements) element.captureDefaults();
    }

    private final HudStyleElement style = new HudStyleElement();

    public HudStyleElement getStyle() {
        return style;
    }

    /** Widget renames: current name -> the name older saved HUD layouts list it under. */
    private static final java.util.Map<String, String> LEGACY_NAMES = java.util.Map.of(
            "Target HUD", "TargetHUD",
            "Effect HUD", "EffectHUD",
            "Bound Modules", "Keybinded Modules");

    /** The name a widget had before it was renamed, or null; lets older saved HUD layouts still apply. */
    public static String legacyName(String name) {
        return name == null ? null : LEGACY_NAMES.get(name);
    }

    /** Puts every widget back to its default position, scale and settings (HUD Style is left alone). */
    public void resetLayout() {
        for (HUDElement element : elements) {
            if (element instanceof HudStyleElement) continue;
            element.resetToDefaults();
        }
    }

    /** Visible widgets only: excludes the HUD Style settings holder. */
    public static boolean isWidget(HUDElement element) {
        return element != null && !(element instanceof HudStyleElement);
    }

    public static double getLastHitReach() {
        return lastHitReach;
    }

    public static void updateLastHitReach(double reach) {
        lastHitReach = reach;
    }

    /** Called from the local vanilla attack seam (client thread) for every accepted attack. */
    public static void updateTarget(net.minecraft.entity.Entity target) {
        // Combat state for "Only In Combat" and kill credit for Session Stats.
        SessionStats.onLocalAttack(target);
        if (target instanceof net.minecraft.entity.LivingEntity living) {
            TargetHudElement.updateTarget(living);
        }
    }

    public void initialize() {
    }

    public void render(net.minecraft.client.gui.DrawContext context, float delta) {
        HudTheme.refresh();
        var mcNow = net.minecraft.client.MinecraftClient.getInstance();
        if (mcNow != null && mcNow.world != lastWorld) {
            lastWorld = mcNow.world;
            lastHitReach = 0.0; // a new world or a disconnect starts the Last Reach readout fresh
        }
        float legacyScale = GpuHudRenderer.viewportScale(context.getScaledWindowWidth(), context.getScaledWindowHeight());
        GpuHudRenderer.render(context, delta);
        renderArmorIcons(context);
        // Keep older optional widgets alive for saved configs; the new performance, coordinates,
        // target, keybind and array-list surfaces are all submitted through the GPU renderer.
        for (HUDElement element : elements) {
            if (!element.isEnabled() || GpuHudRenderer.isGpuWidget(element) || element.isHiddenOutOfCombat()) continue;
            var matrices = context.getMatrices();
            matrices.pushMatrix();
            // Same viewport scale the editor and the GPU widgets use, so saved positions line up.
            matrices.scale(legacyScale, legacyScale);
            // A widget with "Use Own Colours" on draws with its own palette instead of the HUD-wide one.
            boolean ownColours = HudTheme.enter(element);
            try {
                matrices.translate((float) element.getX(), (float) element.getY());
                float scale = (float) element.getScale();
                matrices.scale(scale, scale);
                matrices.translate((float) -element.getX(), (float) -element.getY());
                element.render(context, delta);
            } finally {
                if (ownColours) HudTheme.exit();
                matrices.popMatrix();
            }
        }
    }

    private void renderArmorIcons(net.minecraft.client.gui.DrawContext context) {
        var client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.player == null || client.currentScreen != null) return;
        renderArmorIcons(context, elements,
                GpuHudRenderer.viewportScale(context.getScaledWindowWidth(), context.getScaledWindowHeight()));
    }

    /** Item models over the GPU-drawn armor panel; shared by the HUD and the HUD editor preview. */
    public static void renderArmorIcons(net.minecraft.client.gui.DrawContext context, List<HUDElement> elements,
                                        float viewportScale) {
        var matrices = context.getMatrices();
        // In-game (no screen) honours the widget's Only In Combat; the HUD editor always shows it.
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        boolean inGame = mc == null || mc.currentScreen == null;
        matrices.pushMatrix();
        try {
            matrices.scale(viewportScale, viewportScale);
            for (HUDElement element : elements) {
                if (element instanceof ArmorHudElement armor && armor.isEnabled()
                        && !(inGame && armor.isHiddenOutOfCombat())) {
                    armor.renderItemIcons(context);
                }
            }
        } finally {
            matrices.popMatrix();
        }
    }

    public List<HUDElement> getElements() {
        return elements;
    }

    public HUDElement getElement(String name) {
        for (HUDElement element : elements) {
            if (element.getName().equalsIgnoreCase(name)) {
                return element;
            }
        }
        return null;
    }

    public static HUDManager getInstance() {
        if (instance == null) {
            instance = new HUDManager();
        }
        return instance;
    }
}
