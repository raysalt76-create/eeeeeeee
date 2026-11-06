package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every event a script can listen to with {@code on <name>(ev) { ... }}, with the fields
 * its event record carries. Pure data (no Minecraft types) so the parser, the IDE
 * reference panel and the unit tests share one list; {@link ScriptEvents} fires them.
 */
public final class ScriptEventCatalog {
    private ScriptEventCatalog() { }

    /** One event: its name, what fires it, and the fields of {@code ev}. */
    public record EventInfo(String name, String doc, List<String> fields) { }

    private static final Map<String, EventInfo> EVENTS = new LinkedHashMap<>();

    private static void ev(String name, String doc, String... fields) {
        EVENTS.put(name, new EventInfo(name, doc, List.of(fields)));
    }

    /** Lifecycle handlers take no event parameter. */
    public static final List<String> LIFECYCLE = List.of("enable", "disable", "tick", "tick_post", "tick_end",
            "render", "frame", "trigger");

    /**
     * Events whose handlers can change what happens, and how: {@code cancel()} (optionally with a kind),
     * {@code setResult(v)}, {@code setInput(name, on)} or {@code setLook(dx, dy)}. Everything else is
     * observe-only.
     */
    public static final Map<String, String> RESPONSES = Map.of(
            "attack_input", "cancel() drops this attack click",
            "use_input", "cancel() blocks this use (right-click) attempt",
            "block_interact", "cancel() cancels this block interaction",
            "swap_lock", "cancel(\"swap\") locks other modules' item swaps, cancel(\"attack\") their auto-attacks, cancel() both",
            "jump_input", "setResult(true) makes you jump this tick",
            "movement_input", "setInput(\"forward\"|\"backward\"|\"left\"|\"right\"|\"jump\"|\"sneak\"|\"sprint\", on) changes this tick's movement keys",
            "look_input", "setLook(dx, dy) replaces this mouse movement (degrees)",
            "mouse_sensitivity", "setResult(multiplier 0..1) lowers mouse sensitivity this frame");

    static {
        // Lifecycle (no ev).
        ev("enable", "The script turns on.");
        ev("disable", "The script turns off.");
        ev("tick", "Every game tick (20 per second).");
        ev("tick_post", "After every module's tick has run.");
        ev("tick_end", "At the very end of the game tick.");
        ev("render", "Every HUD render pass. The only place drawText / drawRect / drawOutline / drawLine draw (they do nothing elsewhere). With SS Proof on, give the script category render so its drawing goes to the hidden overlay. Use screenWidth() / screenHeight() for layout.");
        ev("frame", "Every rendered frame, before input is processed.");
        ev("trigger", "The keybind of a oneshot or trigger script was pressed.");
        ev("world_render", "Every world render pass (after the world is drawn).", "tickDelta");
        ev("world_render_end", "At the end of the world render, after every module's world visuals.", "tickDelta");

        // Timing.
        ev("every", "Runs repeatedly: on every(250) { } fires every 250 ms (frame precision).", "elapsed");

        // Input.
        ev("key_press", "A keyboard key went down (no screen open).", "key", "name");
        ev("key_release", "A keyboard key went up (no screen open).", "key", "name");
        ev("mouse_press", "A mouse button went down (no screen open).", "button");
        ev("mouse_release", "A mouse button went up (no screen open).", "button");

        // Combat.
        ev("attack", "You attacked an entity.", "target");
        ev("hurt", "You took damage.", "attacker", "amount", "source", "melee");
        ev("damage", "Any entity took damage.", "entity", "attacker", "melee", "self");
        ev("shield_raise", "A player started blocking with a shield.", "player", "self");
        ev("shield_lower", "A player stopped blocking with a shield.", "player", "self");
        ev("shield_block", "A player's shield blocked a hit.", "player", "self");
        ev("shield_disabled", "A player's shield was disabled (axe).", "player", "self");
        ev("totem_pop", "A player popped a totem.", "player", "self");
        ev("swing", "A player swung their hand.", "player", "self");
        ev("knockback", "The server pushed you (velocity update).", "x", "y", "z", "strength");
        ev("attack_input", "You pressed attack (before the click is used). cancel() drops the click.", "target");
        ev("vanilla_attack", "Minecraft just attacked an entity from a click.", "target", "x", "y", "z");
        ev("mace_hit", "You hit something while holding a mace.");
        ev("swap_lock", "A module asks whether item swaps / auto-attacks are locked right now. cancel(\"swap\"), cancel(\"attack\") or cancel() locks them.", "self");
        ev("explosion", "An explosion packet arrived.");
        ev("packet_receive", "A packet arrived from the server (observe only).", "type");
        ev("packet_send", "A packet was sent to the server (observe only).", "type");

        // Input hooks.
        ev("use_input", "You pressed use / right-click. cancel() blocks it.", "item");
        ev("block_interact", "You are about to interact with a block. cancel() cancels it.", "x", "y", "z", "face", "block");
        ev("block_interact_post", "A block interaction finished.", "x", "y", "z", "face", "hand", "accepted");
        ev("jump_input", "Each movement tick: setResult(true) to jump.", "onGround");
        ev("jump", "You jumped.", "x", "y", "z");
        ev("movement_input", "Each movement tick, your movement keys. Change them with setInput(name, on).",
                "forward", "backward", "left", "right", "jump", "sneak", "sprint");
        ev("look_input", "Your mouse moved (degrees). Replace it with setLook(dx, dy).", "dx", "dy");
        ev("mouse_sensitivity", "Each mouse update: setResult(0..1) to scale sensitivity down.");

        // Items.
        ev("use_start", "A player started using an item (eating, drinking, bow, shield...).", "player", "item", "self", "eating");
        ev("use_stop", "A player stopped using an item.", "player", "item", "self");
        ev("item_switch", "A player's main-hand item changed.", "player", "from", "to", "self");
        ev("slot_change", "Your selected hotbar slot changed.", "from", "to");

        // Movement.
        ev("sprint_change", "A player started or stopped sprinting.", "player", "sprinting", "self");
        ev("sneak_change", "A player started or stopped sneaking.", "player", "sneaking", "self");
        ev("fall_start", "You left the ground.", "y");
        ev("land", "You landed.", "fall", "y");

        // Projectiles and explosives.
        ev("pearl_thrown", "An ender pearl appeared.", "pearl", "owner", "x", "y", "z", "self");
        ev("pearl_landed", "An ender pearl disappeared (landed).", "owner", "x", "y", "z", "self");
        ev("crystal_placed", "An end crystal appeared.", "crystal", "x", "y", "z", "distance");
        ev("crystal_exploded", "An end crystal disappeared.", "x", "y", "z", "distance");
        ev("anchor_charged", "A nearby respawn anchor gained charge.", "x", "y", "z", "charges");
        ev("anchor_exploded", "A nearby charged respawn anchor vanished.", "x", "y", "z");

        // World.
        ev("block_placed", "The server set a block to something solid.", "x", "y", "z", "block");
        ev("block_broken", "The server set a block to air.", "x", "y", "z", "block");
        ev("entity_spawn", "An entity entered the world.", "entity", "type");
        ev("entity_despawn", "An entity left the world.", "type", "x", "y", "z");
        ev("player_join", "A player came into render distance.", "player", "name");
        ev("player_leave", "A player left render distance.", "name");
        ev("chat", "A chat or system message arrived.", "message", "system");
        ev("health_change", "Your health changed.", "old", "health", "delta");
        ev("hunger_change", "Your food level changed.", "old", "food", "delta");
        ev("world_change", "You joined a world or changed dimension.", "dimension");
        ev("setting_change", "One of this script's settings changed.", "name");
    }

    public static boolean isEvent(String name) { return EVENTS.containsKey(name); }

    public static boolean isLifecycle(String name) { return LIFECYCLE.contains(name); }

    /** True when handlers of this event may change the outcome (see {@link #RESPONSES}). */
    public static boolean isRespondable(String name) { return RESPONSES.containsKey(name); }

    public static EventInfo info(String name) { return EVENTS.get(name); }

    public static Map<String, EventInfo> all() { return Collections.unmodifiableMap(EVENTS); }
}
