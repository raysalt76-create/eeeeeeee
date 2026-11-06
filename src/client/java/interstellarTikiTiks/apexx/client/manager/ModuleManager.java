package interstellarTikiTiks.apexx.client.manager;

import interstellarTikiTiks.apexx.client.ApexxAuth;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;
import interstellarTikiTiks.apexx.client.module.hooks.RenderFrameAware;
import interstellarTikiTiks.apexx.client.manager.TargetManager;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.util.Notifier;
// versions/1.21.11 modules
// Blatant module registrations.
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ModuleManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModuleManager.class);
    private static final String FORCE_REMOTE_CLASSLOAD_PROPERTY = "apexx.forceRemoteClassload";
    private static final String[] DEV_MODULE_SCAN_ROOTS = {
            "interstellarTikiTiks/apexx/client/module/implementation",
            "interstellarTikiTiks/apexx/client/module/implementation/combat",
            "interstellarTikiTiks/apexx/client/module/implementation/render",
            "interstellarTikiTiks/apexx/client/module/implementation/targeteffect",
            "interstellarTikiTiks/apexx/client/module/implementation/movement",
            "interstellarTikiTiks/apexx/client/module/implementation/player",
            "interstellarTikiTiks/apexx/client/module/implementation/smp",
            "interstellarTikiTiks/apexx/client/module/implementation/bedwars",
            "interstellarTikiTiks/apexx/client/module/implementation/automations",
            "interstellarTikiTiks/apexx/client/module/implementation/blatant",
            "interstellarTikiTiks/apexx/client/module/implementation/support"
    };
    private static final Set<String> DEV_MODULE_EXCLUSIONS = Set.of(
            "interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule"
    );
    private static final int MODULE_AUTH_GATE_SALT = ((0x29 ^ 0x7E) << 24)
            | ((0x91 ^ 0xD4) << 16)
            | ((0x3B ^ 0x67) << 8)
            | (0x52 ^ 0x18);
    

    private final List<Module> modules = new CopyOnWriteArrayList<>();
    private volatile List<Module> millisecondModules = List.of();
    

    private final Map<Class<?>, Object> hookTypeCache = new ConcurrentHashMap<>();
    private static final Object HOOK_ABSENT = new Object();
    private static final Module[] NO_MODULES = new Module[0];
    /**
     * Hook type -> every registered module implementing it, in registration order (enabled or not). Hook lookups
     * run per packet (netty thread), per input event and per rendered entity, so scanning all modules with
     * isInstance on each call was the dominant cost. Arrays are immutable once published; on any registration
     * change the whole map is swapped for a fresh one, so a lookup racing a registration can only populate
     * the discarded map, never leave a stale entry in the live one.
     */
    private volatile Map<Class<?>, Module[]> implementerCache = new ConcurrentHashMap<>();
    private final MinecraftClient mc = MinecraftClient.getInstance();
    public ModuleManager() {
        if (useLocalDevModuleDiscovery()) {
            int discovered = discoverAndRegisterLocalDevModules();
            LOGGER.debug("[Classload] Dev local module discovery registered {} module(s).", discovered);
        } else {
            int discovered = discoverAndRegisterProductionModules();
            LOGGER.debug("[Classload] Module discovery registered {} module(s).", discovered);
        }
        registerCoreModules();
        invalidateHookCaches();
        refreshMillisecondModules();
        long combatCount = modules.stream().filter(module -> module.getCategory() == Category.COMBAT && !module.isHidden()).count();
        LOGGER.info("[UI catalog] Registered {} modules; visible Combat modules: {}.", modules.size(), combatCount);
    }

    /** Main-JAR modules are excluded from payload discovery and must be registered explicitly. */
    private void registerCoreModules() {
        if (getModule("ClickGUI") != null) return;
        try {
            modules.add(new ClickGUIModule());
            invalidateHookCaches();
        } catch (Throwable failure) {
            LOGGER.error("[UI catalog] Failed to register the core ClickGUI settings module.", failure);
        }
    }

    public static boolean useLocalDevModuleDiscovery() {
        if (isForceRemoteClassloadEnabled()) {
            return false;
        }
        

        

        

        return isFabricDevelopmentRuntime();
    }

    public static boolean isExplicitLocalDevDiscoveryEnabled() {
        

        return false;
    }

    public static boolean isForceRemoteClassloadEnabled() {
        return Boolean.getBoolean(FORCE_REMOTE_CLASSLOAD_PROPERTY);
    }

    public static boolean isFabricDevelopmentRuntime() {
        try {
            return FabricLoader.getInstance().isDevelopmentEnvironment();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String describeClassloadRuntimeMode() {
        if (useLocalDevModuleDiscovery()) {
            return "local module discovery (Fabric dev runtime)";
        }
        if (isForceRemoteClassloadEnabled()) {
            return "remote bundle loading (forced by JVM property)";
        }
        return "remote bundle loading";
    }

    private int discoverAndRegisterLocalDevModules() {
        Set<String> classNames = new LinkedHashSet<>();
        ClassLoader loader = ModuleManager.class.getClassLoader();

        for (String root : DEV_MODULE_SCAN_ROOTS) {
            try {
                Enumeration<URL> resources = loader.getResources(root);
                while (resources.hasMoreElements()) {
                    URL url = resources.nextElement();
                    if (!"file".equalsIgnoreCase(url.getProtocol())) {
                        continue;
                    }
                    collectModuleClasses(Path.of(URI.create(url.toString())), root.replace('/', '.'), classNames);
                }
            } catch (Exception e) {
                LOGGER.debug("[Classload] Failed to scan dev modules under [{}]: {}", root, e.getMessage());
            }
        }

        int registered = 0;
        for (String className : classNames) {
            if (DEV_MODULE_EXCLUSIONS.contains(className) || className.contains("$")) {
                continue;
            }
            try {
                Class<?> rawClass = Class.forName(className, false, loader);
                if (!Module.class.isAssignableFrom(rawClass)) {
                    continue;
                }
                if (Modifier.isAbstract(rawClass.getModifiers()) || rawClass.isInterface()) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                Class<? extends Module> moduleClass = (Class<? extends Module>) rawClass;
                Module module = moduleClass.getDeclaredConstructor().newInstance();
                if (getModule(module.getName()) != null) {
                    continue;
                }
                modules.add(module);
                registered++;
            } catch (NoSuchMethodException ignored) {
                

            } catch (Throwable t) {
                LOGGER.debug("[Classload] Failed to register local dev module [{}].", className, t);
            }
        }
        return registered;
    }

    private int discoverAndRegisterProductionModules() {
        String[] classNames = {
            "interstellarTikiTiks.apexx.client.module.implementation.NoBreakDelay",
            "interstellarTikiTiks.apexx.client.module.implementation.FastPlace",
            "interstellarTikiTiks.apexx.client.module.implementation.Prevent",
            "interstellarTikiTiks.apexx.client.module.implementation.SnapTap",
            "interstellarTikiTiks.apexx.client.module.implementation.SelfDestruct",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoJumpReset",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.Nick",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.SmartWTap",
            "interstellarTikiTiks.apexx.client.module.implementation.AutoDrain",
            "interstellarTikiTiks.apexx.client.module.implementation.player.WebEscape",
            "interstellarTikiTiks.apexx.client.module.implementation.player.AutoBuild",
            "interstellarTikiTiks.apexx.client.module.implementation.AutoTool",
            "interstellarTikiTiks.apexx.client.module.implementation.ConfigBind",
            "interstellarTikiTiks.apexx.client.module.implementation.ElytraSwap",
            "interstellarTikiTiks.apexx.client.module.implementation.Fakelag",
            "interstellarTikiTiks.apexx.client.module.implementation.automations.AutoPearl",
            "interstellarTikiTiks.apexx.client.module.implementation.ObsidianGlow",
            "interstellarTikiTiks.apexx.client.module.implementation.Sprint",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AlienWC",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AimAssist",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoCart",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.FastCart",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoXBowCart",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.CartRefill",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoCrystal",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoHitCrystal",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoAnchor",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.SafeAnchor",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoWeb",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoElytra",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoMace",
            // Registered AFTER AutoMace: AutoMace reads SpearMaceDtap.isComboActive() to stay paused
            // for the combo's fall, so AutoMace's tick never runs between the dtap's swap and hit.
            "interstellarTikiTiks.apexx.client.module.implementation.combat.SpearMaceDtap",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.Strafe",
            // After AutoMace/AutoElytra: it drives both (elytra drop notification, lock event) within one tick.
            "interstellarTikiTiks.apexx.client.module.implementation.combat.MaceDoubleTap",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.PearlGrapple",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoShieldBreak",
            "interstellarTikiTiks.apexx.client.module.implementation.automations.AutoShield",
            "interstellarTikiTiks.apexx.client.module.implementation.automations.AutoGapple",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.PerfectWindcharge",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.BowAimbot",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.ChompzaInstaCatch",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.LavaMacro",
            "interstellarTikiTiks.apexx.client.module.implementation.movement.AutoLunge",
            "interstellarTikiTiks.apexx.client.module.implementation.blatant.InstantLunge",
            "interstellarTikiTiks.apexx.client.module.implementation.movement.SpearAssist",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoHeal",
            "interstellarTikiTiks.apexx.client.module.implementation.player.FastExp",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.Replenish",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.PearlCatch",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.PearlDisrupter",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.PotRefill",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.SwordHit",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AntiMiss",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.TriggerBot",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.SpearReach",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.HitSelect",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.Backtrack",
            "interstellarTikiTiks.apexx.client.module.implementation.render.Ambience",
            "interstellarTikiTiks.apexx.client.module.implementation.render.Nametags",
            "interstellarTikiTiks.apexx.client.module.implementation.Trajectory",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoChorus",
            "interstellarTikiTiks.apexx.client.module.implementation.render.FullBright",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.BreachSwap",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.MaceShieldDrain",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.BowSpam",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.PearlChase",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.KnockbackDisplacement",
            "interstellarTikiTiks.apexx.client.module.implementation.player.AntiTranslation",
            "interstellarTikiTiks.apexx.client.module.implementation.player.InvCleaner",
            "interstellarTikiTiks.apexx.client.module.implementation.player.ChestSteal",
            "interstellarTikiTiks.apexx.client.module.implementation.player.AntiBot",
            "interstellarTikiTiks.apexx.client.module.implementation.render.DashTrails",
            "interstellarTikiTiks.apexx.client.module.implementation.render.ShaderEsp",
            "interstellarTikiTiks.apexx.client.module.implementation.render.MaceImpact",
            "interstellarTikiTiks.apexx.client.module.implementation.movement.InventoryMove",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.Flight",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.Speed",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.NoFall",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.NoSlow",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.AutoWalk",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.Freecam",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.Xray",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.ItemESP",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.BlockESP",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoSpearReach",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoWater",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.CartBlocker",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.ShiftTap",
            "interstellarTikiTiks.apexx.client.module.implementation.bedwars.Scaffold",
            "interstellarTikiTiks.apexx.client.module.implementation.bedwars.AutoHeader",
            "interstellarTikiTiks.apexx.client.module.implementation.bedwars.AutoJump",
            "interstellarTikiTiks.apexx.client.module.implementation.bedwars.AutoBlock",
            "interstellarTikiTiks.apexx.client.module.implementation.bedwars.LeftClicker",
            "interstellarTikiTiks.apexx.client.module.implementation.bedwars.RightClicker",
            "interstellarTikiTiks.apexx.client.module.implementation.bedwars.ClickAssist",
            "interstellarTikiTiks.apexx.client.module.implementation.bedwars.HitFlick",
            "interstellarTikiTiks.apexx.client.module.implementation.bedwars.AutoEat",
            "interstellarTikiTiks.apexx.client.module.implementation.bedwars.AutoSoup",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.SwordAura",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.PlayerFreeze",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.XBowCartProtection",
            "interstellarTikiTiks.apexx.client.module.implementation.player.Clutch",
            "interstellarTikiTiks.apexx.client.module.implementation.render.TargetESP",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.BaseFinder",
            "interstellarTikiTiks.apexx.client.module.implementation.render.LogoutSpots",
            "interstellarTikiTiks.apexx.client.module.implementation.render.NewChunks",
            "interstellarTikiTiks.apexx.client.module.implementation.render.CustomCrosshair",
            "interstellarTikiTiks.apexx.client.module.implementation.render.ViewModel",
            "interstellarTikiTiks.apexx.client.module.implementation.render.NoRender",
            "interstellarTikiTiks.apexx.client.module.implementation.render.KillEffects",
            "interstellarTikiTiks.apexx.client.module.implementation.render.ArmorWarnings",
            "interstellarTikiTiks.apexx.client.module.implementation.render.TotemPopCustomizer",
            "interstellarTikiTiks.apexx.client.module.implementation.render.HitColor",
            "interstellarTikiTiks.apexx.client.module.implementation.render.Notifications",
            "interstellarTikiTiks.apexx.client.module.implementation.render.HitFx",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.VisualRange",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.DeathCoords",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.AutoFarm",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.AutoMine",
            "interstellarTikiTiks.apexx.client.module.implementation.smp.StaffDetector",
            "interstellarTikiTiks.apexx.client.module.implementation.player.AntiAFK",
            "interstellarTikiTiks.apexx.client.module.implementation.player.KitLayout",
            "interstellarTikiTiks.apexx.client.module.implementation.HudEditor",
            "interstellarTikiTiks.apexx.client.module.implementation.render.HudRestyle",
            "interstellarTikiTiks.apexx.client.module.implementation.render.CrystalFX",
            "interstellarTikiTiks.apexx.client.module.implementation.player.HotbarScrollLock",
            "interstellarTikiTiks.apexx.client.module.implementation.render.JumpCircles",
            "interstellarTikiTiks.apexx.client.module.implementation.render.Breadcrumbs",
            "interstellarTikiTiks.apexx.client.module.implementation.render.SwingTrails",
            "interstellarTikiTiks.apexx.client.module.implementation.render.ProjectileTrails",
            "interstellarTikiTiks.apexx.client.module.implementation.render.PearlLandingMarker",
            "interstellarTikiTiks.apexx.client.module.implementation.render.BlockOutline",
            "interstellarTikiTiks.apexx.client.module.implementation.render.DamageNumbers",
            "interstellarTikiTiks.apexx.client.module.implementation.render.SkeletonEsp",
            "interstellarTikiTiks.apexx.client.module.implementation.render.CritFlash",
            "interstellarTikiTiks.apexx.client.module.implementation.render.MaceFallIndicator",
            "interstellarTikiTiks.apexx.client.module.implementation.render.ShieldStatus",
            "interstellarTikiTiks.apexx.client.module.implementation.render.FriendGlow",
            "interstellarTikiTiks.apexx.client.module.implementation.render.CooldownRing",
            // Premium-only (premium-only-modules.txt): absent from the free payload, where registration just skips it.
            "interstellarTikiTiks.apexx.client.module.implementation.render.Cosmetics",
            "interstellarTikiTiks.apexx.client.module.implementation.AutoTotem",
            // Premium macro/script studio (hidden; absent from the free payload).
            "interstellarTikiTiks.apexx.client.module.implementation.studio.MacroStudio",
        };
        int registered = 0;
        int failures = 0;
        String firstFailure = null;
        ClassLoader loader = ModuleManager.class.getClassLoader();
        for (String className : classNames) {
            try {
                if (hasModuleOfClass(className)) {
                    continue;
                }
                Class<?> clazz = Class.forName(className, true, loader);
                Module m = (Module) clazz.getDeclaredConstructor().newInstance();
                if (getModule(m.getName()) != null) continue;
                modules.add(m);
                registered++;
            } catch (ClassNotFoundException notInLoader) {
                // Payload-only module: absent from the loader jar until the payload registers it.
                LOGGER.debug("[UI catalog] {} not in loader jar; left to the payload.", className);
            } catch (Throwable t) {
                failures++;
                if (firstFailure == null) {
                    firstFailure = className + " (" + t.getClass().getSimpleName() + ")";
                }
            }
        }
        if (failures > 0) {
            LOGGER.warn("[UI catalog] Production discovery skipped {} class(es); first failure: {}.", failures, firstFailure);
        }
        return registered;
    }
    private void collectModuleClasses(Path rootPath, String packageName, Set<String> classNames) throws IOException {
        if (!Files.isDirectory(rootPath)) {
            return;
        }
        try (var stream = Files.walk(rootPath)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .forEach(path -> {
                        String relative = rootPath.relativize(path).toString().replace('\\', '/');
                        if (!relative.endsWith(".class")) {
                            return;
                        }
                        String suffix = relative.substring(0, relative.length() - 6).replace('/', '.');
                        if (suffix.isBlank()) {
                            return;
                        }
                        classNames.add(packageName + "." + suffix);
                    });
        }
    }

    // Modules that threw during a tick. Re-running one would spam the log and keep
    // re-entering whatever half-finished state the throw left behind, so it stays
    // parked for the rest of the session.
    private final Set<String> brokenModules = ConcurrentHashMap.newKeySet();

    /** The callbacks that run module code and are guarded separately, so one failing hook does not silence the others. */
    private enum Channel { TICK, TICK_POST, TICK_END, RENDER, RENDER_FRAME, MS, WORLD_RENDER }

    private final Map<String, FaultGate[]> faultGates = new ConcurrentHashMap<>();

    private FaultGate gate(Module module, Channel channel) {
        // Plain get first: this runs for every module callback (including the 1 kHz onMs path).
        FaultGate[] existing = faultGates.get(module.getName());
        if (existing != null) return existing[channel.ordinal()];
        FaultGate[] gates = faultGates.computeIfAbsent(module.getName(), k -> {
            FaultGate[] created = new FaultGate[Channel.values().length];
            for (int i = 0; i < created.length; i++) created[i] = new FaultGate();
            return created;
        });
        return gates[channel.ordinal()];
    }

    private boolean inWorld() {
        try {
            return mc.player != null && mc.world != null;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /**
     * Runs one callback of one module behind its fault gate. Returns false when the callback was suspended or
     * threw. A fault clears the module's runtime state (the half-finished work the throw left behind) and is
     * judged by {@link FaultGate}: a pause, a suspension with backoff, or, only for a gate that keeps tripping,
     * disabling the module with a message.
     */
    private boolean runGuarded(Module module, Channel channel, long now, Runnable body) {
        FaultGate gate = gate(module, channel);
        if (!gate.allowed(now)) return false;
        try {
            interstellarTikiTiks.apexx.client.util.ModuleExecutionContext.run(module, body);
            gate.onSuccess(now);
            return true;
        } catch (Throwable t) {
            try { module.releaseRuntimeState(); } catch (Throwable ignored) { /* cleanup must not mask the fault */ }
            String summary = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            FaultGate.Action action = gate.onFault(now, inWorld(), summary);
            ClientInternalLog.error(module.getName(), "Module failed during " + channel + " (" + action + "): " + t.getMessage(), t);
            LOGGER.debug("Module [{}] failed during {}; {}", module.getName(), channel, action, t);
            if (action == FaultGate.Action.SUSPEND) {
                ClientInternalLog.warn(module.getName(), channel + " paused after repeated failures; it will be tried again.");
            } else if (action == FaultGate.Action.DISABLE) {
                brokenModules.add(module.getName());
                ClientInternalLog.error(module.getName(), "Module disabled: " + channel + " kept failing, repeatedly, over minutes.", t);
                try {
                    module.setEnabled(false);
                    notifyFaultDisable(module, "repeated " + channel.name().toLowerCase(java.util.Locale.ROOT) + " failures");
                } catch (Throwable disableFailure) {
                    ClientInternalLog.error(module.getName(), "Failed while disabling after repeated failures.", disableFailure);
                }
                gate.reset();
            }
            return false;
        }
    }

    private final AuthBackstop<Module> authBackstop = new AuthBackstop<>();

    /**
     * Called every client tick with whether a live session exists. Without one, modules are switched off
     * after a short grace period; when it returns, the ones switched off here come back.
     */
    public void enforceAuth(boolean authorized) {
        authBackstop.onTick(authorized, System.nanoTime(), modules,
                Module::isEnabled, module -> module.setEnabled(false), module -> module.setEnabled(true));
    }

    /** Joining or leaving a world is a fresh start: nothing stays suspended or parked because of an earlier world. */
    public void resetFaults() {
        faultGates.clear();
        brokenModules.clear();
    }

    public void unpark(String moduleName) {
        if (moduleName != null) {
            brokenModules.remove(moduleName);
            FaultGate[] gates = faultGates.get(moduleName);
            if (gates != null) for (FaultGate gate : gates) gate.reset();
        }
    }

    // Re-entrancy guard. Module ticking used to be driven from two places at once --
    // MinecraftClientMixin's tick HEAD and a second, independent loop in
    // ApexxClient's END_CLIENT_TICK handler -- so every module ran twice per game
    // tick and every tick-counted delay in the client was silently halved. The
    // dispatch is consolidated on the mixin now; this stops a future second caller
    // from quietly reintroducing the same bug.
    private boolean ticking = false;

    private volatile long tickDispatchCount = 0L;

    /** Number of times {@link #onTick()} actually ran its module loop (diagnostics only). */
    public long getTickDispatchCount() {
        return tickDispatchCount;
    }

    public boolean isParked(String moduleName) {
        return moduleName != null && brokenModules.contains(moduleName);
    }

    /** One line per module that is parked or currently carries callback faults (diagnostics only). */
    public List<String> describeFaults() {
        List<String> lines = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Module module : modules) {
            String name = module.getName();
            boolean parked = brokenModules.contains(name);
            FaultGate[] gates = faultGates.get(name);
            StringBuilder detail = new StringBuilder();
            if (gates != null) {
                for (Channel channel : Channel.values()) {
                    FaultGate gate = gates[channel.ordinal()];
                    if (gate.faulting(now)) detail.append(detail.length() == 0 ? "" : "; ").append(channel).append(' ').append(gate.describe(now));
                }
            }
            if (!parked && detail.length() == 0) continue;
            lines.add(name + ": parked=" + parked + (detail.length() == 0 ? "" : ", " + detail));
        }
        return lines;
    }

    public void onTick() {
        if (ticking) {
            return;
        }
        ticking = true;
        tickDispatchCount++;
        try {
            try {
                TargetManager.update();
            } catch (Throwable targetFailure) {
                // Target cache failure must not skip every module for the entire tick.
                ClientInternalLog.error("TargetManager", "Target cache update failed", targetFailure);
            }
            long now = System.currentTimeMillis();
            for (Module module : modules) {
                if (!module.isEnabled() && !module.shouldTickWhenDisabled()) continue;
                if (brokenModules.contains(module.getName())) continue;
                if (interstellarTikiTiks.apexx.client.util.combat.AutomationPriority.suspends(module)) continue;
                runGuarded(module, Channel.TICK, now, module::onTick);
            }
        } finally {
            ticking = false;
        }
    }

    public void onTickPost() {
        long now = System.currentTimeMillis();
        for (Module module : modules) {
            if (!module.isEnabled() && !module.shouldTickWhenDisabled()) continue;
            if (brokenModules.contains(module.getName())) continue;
            if (interstellarTikiTiks.apexx.client.util.combat.AutomationPriority.suspends(module)) continue;
            runGuarded(module, Channel.TICK_POST, now, module::onTickPost);
        }
    }

    public void onTickEnd() {
        long now = System.currentTimeMillis();
        for (Module module : modules) {
            if (!module.isEnabled()) continue;
            if (brokenModules.contains(module.getName())) continue;
            if (interstellarTikiTiks.apexx.client.util.combat.AutomationPriority.suspends(module)) continue;
            runGuarded(module, Channel.TICK_END, now, module::onTickEnd);
        }
    }

    public void onRender(DrawContext context, float tickDelta) {
        long now = System.currentTimeMillis();
        for (Module module : modules) {
            if (!module.isEnabled()) continue;
            runGuarded(module, Channel.RENDER, now, () -> module.onRender(context, tickDelta));
        }
    }

    /**
     * Dispatches high-cadence render callbacks through the same fault boundary as every other
     * module callback. A transient render-stack or world-transition failure pauses only that callback;
     * a callback that keeps failing is suspended with growing pauses instead of crashing the client or
     * silently running a broken callback forever.
     */
    public void onRenderFrame() {
        long now = System.currentTimeMillis();
        for (Module module : modules) {
            if (!module.isEnabled() || !(module instanceof RenderFrameAware hook)) continue;
            if (brokenModules.contains(module.getName())) continue;
            runGuarded(module, Channel.RENDER_FRAME, now, hook::onRenderFrame);
        }
    }

    public void onMs() {
        long now = System.currentTimeMillis();
        for (Module module : millisecondModules) {
            if (!module.isEnabled()) continue;
            if (interstellarTikiTiks.apexx.client.util.combat.AutomationPriority.suspends(module)) continue;
            // onMs runs on the shared 1 kHz timer thread. It used to switch the module off on the first
            // exception, so one race with the client thread (the world changing under a callback) was
            // enough to disable a module until the player noticed. It now goes through the same gate as every
            // other callback: a failure pauses it briefly, only a callback that keeps failing is suspended.
            runGuarded(module, Channel.MS, now, module::onMs);
        }
    }

    public boolean shouldBlockNanoVG() {
        if (mc.currentScreen == null)
            return false;

        return mc.currentScreen instanceof ChatScreen ||
                mc.currentScreen instanceof net.minecraft.client.gui.screen.multiplayer.ConnectScreen ||
                mc.currentScreen.getClass().getName().endsWith("DownloadingTerrainScreen") ||
                mc.currentScreen instanceof net.minecraft.client.gui.screen.world.LevelLoadingScreen;
    }

    // Reused per-render-frame MatrixStack — avoids allocating a new object for
    // EVERY module EVERY frame (was the primary source of right-click render lag).
    private final MatrixStack sharedWorldRenderStack = new MatrixStack();

    /**
     * Whether any currently-enabled module actively renders 3D world-space geometry.
     * Gates the stream-proof visual capture layer so no FBO capture occurs during gameplay
     * when only combat or non-render modules are running.
     */
    public boolean hasActiveWorldRender() {
        for (Module module : modules) {
            if (module.isEnabled() && module.rendersInWorld()) {
                return true;
            }
        }
        return false;
    }

    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        long now = System.currentTimeMillis();
        for (Module module : modules) {
            if (!module.isEnabled()) continue;
            // Reset the shared stack to match the incoming matrices, then hand it to the module. This
            // avoids allocating a new MatrixStack object per-module per-frame (which caused severe GC
            // pressure at high FPS).
            runGuarded(module, Channel.WORLD_RENDER, now, () -> {
                sharedWorldRenderStack.loadIdentity();
                sharedWorldRenderStack.multiplyPositionMatrix(matrices.peek().getPositionMatrix());
                module.onWorldRender(sharedWorldRenderStack, tickDelta);
            });
        }
    }

    private static void notifyFaultDisable(Module module, String reason) {
        if (module == null || "Notifications".equalsIgnoreCase(module.getName())) return;
        try {
            Notifier.error(module.getName(), "Disabled after " + reason + ". Check client logs for details.");
        } catch (Throwable ignored) {
        }
    }

    private boolean passesAuthGate() {
        try {
            return ApexxAuth.isAuthenticated() && ApexxAuth.authGateToken(MODULE_AUTH_GATE_SALT) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public List<Module> getModules() {
        return modules;
    }

    public boolean addModuleAfter(@Nullable String existingModuleName, @NotNull Module module) {
        if (module == null)
            return false;
        if (getModule(module.getName()) != null)
            return false;

        int insertAfter = -1;
        if (existingModuleName != null && !existingModuleName.isBlank()) {
            for (int i = 0; i < modules.size(); i++) {
                Module existing = modules.get(i);
                if (existing != null && existing.getName().equalsIgnoreCase(existingModuleName)) {
                    insertAfter = i;
                    break;
                }
            }
        }

        if (insertAfter >= 0 && insertAfter + 1 <= modules.size()) {
            modules.add(insertAfter + 1, module);
            invalidateHookCaches();
            refreshMillisecondModules();
            return true;
        }

        modules.add(module);
        invalidateHookCaches();
        refreshMillisecondModules();
        return true;
    }

    public boolean removeModule(String name) {
        if (name == null || name.isBlank())
            return false;
        Module module = getModule(name);
        if (module == null)
            return false;

        try {
            module.setEnabled(false);
        } catch (Throwable ignored) {
        }

        module.releaseRuntimeState();
        interstellarTikiTiks.apexx.client.event.EventBus.unregister(module);
        boolean removed = modules.remove(module);
        if (removed) {
            invalidateHookCaches();
            refreshMillisecondModules();
        }
        return removed;
    }

    /**
     * Disable and unregister every module. Used by sign-out / self-destruct to fully unload the
     * client: after this the module list is empty, all hooks are dropped, and nothing ticks. The
     * payload's module classes stay in memory until GC (they can't be force-unloaded safely), but
     * nothing references or runs them, which is what "unload" means observationally.
     */
    public void unregisterAll() {
        try {
            for (Module m : modules) {
                try { m.setEnabled(false); } catch (Throwable ignored) {}
                m.releaseRuntimeState();
                interstellarTikiTiks.apexx.client.event.EventBus.unregister(m);
            }
        } catch (Throwable ignored) {}
        modules.clear();
        millisecondModules = List.of();
        invalidateHookCaches();
    }

    /**
     * The 1 ms sequencer timer also dispatches module millisecond hooks. Keep a compact snapshot
     * of only modules that override the base no-op so the common timer path does not scan every
     * enabled module 1,000 times per second.
     */
    private void refreshMillisecondModules() {
        List<Module> snapshot = new ArrayList<>();
        for (Module module : modules) {
            try {
                if (module.getClass().getMethod("onMs").getDeclaringClass() != Module.class) {
                    snapshot.add(module);
                }
            } catch (NoSuchMethodException ignored) {
                // A module without a discoverable public hook inherits the base no-op.
            } catch (Throwable unresolvable) {
                // getMethod resolves every public method signature of the class, so a missing type
                // anywhere in it (a LinkageError, not a reflection exception) lands here. Registration must
                // never fail over that: dispatch the hook, which is harmless for a module that inherits the no-op.
                snapshot.add(module);
            }
        }
        millisecondModules = snapshot.isEmpty() ? List.of() : List.copyOf(snapshot);
    }

    /** Display order only (alphabetical); tick and callback dispatch keep the registration order of {@link #modules}. */
    public List<Module> getModulesForCategory(Category category) {
        return modules.stream()
                .filter(module -> module.getCategory() == category)
                .sorted(java.util.Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    public boolean hasModuleOfClass(String className) {
        if (className == null) return false;
        for (Module module : modules) {
            if (module != null && className.equals(module.getClass().getName())) return true;
        }
        return false;
    }

    public @Nullable Module getModule(String name) {
        if (name == null) return null;
        for (Module module : modules) {
            if (module.getName().equalsIgnoreCase(name)) {
                return module;
            }
            if ((module.getName().equalsIgnoreCase("AutoSprint") && name.equalsIgnoreCase("Sprint"))
                    || (module.getName().equalsIgnoreCase("Sprint") && name.equalsIgnoreCase("AutoSprint"))) {
                return module;
            }
            if ((module.getName().equalsIgnoreCase("AutoXBowCart") && (name.equalsIgnoreCase("XboxCart") || name.equalsIgnoreCase("KeyXBowCart")))
                    || (module.getName().equalsIgnoreCase("XboxCart") && name.equalsIgnoreCase("AutoXBowCart"))) {
                return module;
            }
        }
        return null;
    }

    public <T extends Module> @Nullable T getModule(Class<T> moduleType) {
        if (moduleType == null) {
            return null;
        }
        for (Module module : modules) {
            if (moduleType.isInstance(module)) {
                return moduleType.cast(module);
            }
        }
        return null;
    }

    public <T extends Module> @Nullable T getEnabledModule(Class<T> moduleType) {
        T module = getModule(moduleType);
        if (module == null || !module.isEnabled()) {
            return null;
        }
        return module;
    }

    public <T> @Nullable T getHook(Class<T> hookType) {
        if (hookType == null) return null;
        Object cached = hookTypeCache.get(hookType);
        if (cached == HOOK_ABSENT) return null;
        if (cached != null) return hookType.cast(cached);
        

        for (Module module : modules) {
            if (hookType.isInstance(module)) {
                hookTypeCache.put(hookType, module);
                return hookType.cast(module);
            }
        }
        hookTypeCache.put(hookType, HOOK_ABSENT);
        return null;
    }

    public <T> @Nullable T getEnabledHook(Class<T> hookType) {
        if (hookType == null) return null;

        // Fast path: the cached/first implementer, if it is enabled.
        T hook = getHook(hookType);
        if (hook != null && !(hook instanceof Module m && !m.isEnabled())) {
            return hook;
        }
        // The cached implementer is absent or DISABLED (e.g. it was locked off by an
        // overriding module). Fall through and return the first ENABLED
        // implementer instead of giving up — otherwise a locked-off earlier module would
        // permanently shadow a live one for singular hooks (AttackTargetAware).
        for (Module module : implementers(hookType)) {
            if (module.isEnabled()) {
                return hookType.cast(module);
            }
        }
        return null;
    }

    public <T> List<T> getEnabledHooks(Class<T> hookType) {
        if (hookType == null) {
            return List.of();
        }
        // Called on hot paths -- once per outgoing/incoming packet, per mouse move and per rendered
        // entity -- where usually no module implements the hook. Only the cached implementers are
        // checked, and the list is allocated on the first enabled match, so the common case
        // allocates nothing. Order and enabled-filtering are unchanged.
        Module[] candidates = implementers(hookType);
        if (candidates.length == 0) return List.of();
        List<T> hooks = null;
        for (Module module : candidates) {
            if (module.isEnabled()) {
                if (hooks == null) {
                    hooks = new ArrayList<>(candidates.length);
                }
                hooks.add(hookType.cast(module));
            }
        }
        return hooks == null ? List.of() : hooks;
    }

    /** Registered modules implementing {@code hookType}, in registration order. Never mutate the result. */
    private Module[] implementers(Class<?> hookType) {
        Map<Class<?>, Module[]> cache = implementerCache;
        Module[] cached = cache.get(hookType);
        if (cached != null) return cached;
        int count = 0;
        Module[] found = null;
        for (Module module : modules) {
            if (hookType.isInstance(module)) {
                if (found == null) found = new Module[4];
                else if (count == found.length) found = java.util.Arrays.copyOf(found, count * 2);
                found[count++] = module;
            }
        }
        Module[] result = count == 0 ? NO_MODULES : java.util.Arrays.copyOf(found, count);
        cache.put(hookType, result);
        return result;
    }

    private void invalidateHookCaches() {
        hookTypeCache.clear();
        implementerCache = new ConcurrentHashMap<>();
    }
}
