package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The "Priority" of an Automations module.
 *
 * <p><b>Normal</b> (default): the automation yields. If another module takes the hotbar slot or the
 * use key, the automation lets go (see {@code HotbarHoldModule}).</p>
 *
 * <p><b>High</b>: when the automation is raised, every other module is cancelled through its own
 * owner-scoped {@link Module#releaseRuntimeState()} (queued actions, rotation, simulated input and
 * slot intent, with slot restores going through {@link SlotSync}). While it stays raised, the tick
 * and millisecond dispatch of those modules is held back by {@code ModuleManager}, so nothing new
 * starts and what they were doing resumes from their own state once the automation lets go. Render,
 * client and hidden modules are not held. Event-bus and mixin-driven
 * reactions of other modules are not intercepted.</p>
 */
public final class AutomationPriority {
    public static final String NORMAL = "Normal";
    public static final String HIGH = "High";

    private static final Set<Module> raised = Collections.newSetFromMap(new IdentityHashMap<>());

    private AutomationPriority() {
    }

    public static ModeSetting createSetting() {
        return new ModeSetting("Priority", NORMAL, NORMAL, HIGH)
                .setDescription("Normal: yields when another module takes the slot or use key. High: cancels every other module's in-flight action when raised and holds their ticks until it lets go.");
    }

    public static boolean isHigh(ModeSetting setting) {
        return setting != null && HIGH.equals(setting.getCurrentMode());
    }

    /** Raises {@code owner}: cancels all other modules' in-flight work and holds their dispatch. */
    public static void raise(Module owner) {
        if (owner == null) return;
        synchronized (raised) {
            if (!raised.add(owner)) return;
        }
        if (Apexx.getInstance() == null || Apexx.getInstance().getModuleManager() == null) return;
        for (Module other : Apexx.getInstance().getModuleManager().getModules()) {
            if (other == owner || !isHeldBack(other)) continue;
            try {
                other.releaseRuntimeState();
            } catch (Throwable t) {
                ClientInternalLog.error(owner.getName(), "Failed to cancel " + other.getName(), t);
            }
        }
    }

    public static void lower(Module owner) {
        if (owner == null) return;
        synchronized (raised) {
            raised.remove(owner);
        }
    }

    /** True when a raised automation holds {@code module}'s tick and millisecond dispatch back. */
    public static boolean suspends(Module module) {
        synchronized (raised) {
            if (raised.isEmpty() || raised.contains(module)) return false;
        }
        return isHeldBack(module);
    }

    private static boolean isHeldBack(Module module) {
        Category category = module.getCategory();
        return category != Category.CLIENT && category != Category.RENDER && !module.isHidden();
    }
}
