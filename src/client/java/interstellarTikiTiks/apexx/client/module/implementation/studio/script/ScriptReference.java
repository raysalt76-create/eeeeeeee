package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import interstellarTikiTiks.apexx.client.gui.gpuui.kit.*;

import java.util.ArrayList;
import java.util.List;

/**
 * The scripting language and host API as shown in the IDE reference panel. The host function names
 * here are checked against {@code HostApi} by a unit test, so this list cannot silently drift.
 */
public final class ScriptReference {
    private ScriptReference() { }

    /** One reference row. {@code insert} is what clicking it types into the editor. */
    public record Entry(String group, String name, String signature, String doc, String insert, boolean hostFunction) { }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private static void fn(String group, String signature, String doc) {
        String name = signature.substring(0, signature.indexOf('('));
        boolean noArgs = signature.endsWith("()");
        ENTRIES.add(new Entry(group, name, signature, doc, noArgs ? name + "()" : name + "(", true));
    }

    private static void snippet(String name, String doc, String insert) {
        ENTRIES.add(new Entry("Language", name, name, doc, insert, false));
    }

    private static void entry(String group, String name, String doc, String insert) {
        ENTRIES.add(new Entry(group, name, name, doc, insert, false));
    }

    static {
        snippet("module", "A script is one module block.", "module \"My Script\" {\n  category misc\n  description \"\"\n\n  on tick {\n  }\n}\n");
        snippet("type", "toggle (default), oneshot (key runs 'on trigger' once) or trigger (module is a master switch, each key press runs 'on trigger').", "type trigger\n");
        snippet("key", "Default keybind, used until the player sets one. Names: \"R\", \"F4\", \"SPACE\", \"MOUSE4\", \"LEFT_SHIFT\"...", "key \"R\"\n");
        snippet("setting slider", "A number setting. Add 'jitter N' for a Gaussian StDev slider next to it (read with sample(\"name\")).", "setting slider delay = 50 range 0..500 jitter 10 \"Delay in ms\"\n");
        snippet("setting toggle", "An on/off setting. Add 'bindable' to give it its own keybind.", "setting toggle enabled = true bindable\n");
        snippet("setting mode", "A pick-one setting.", "setting mode aim = \"Head\" of \"Head\", \"Body\"\n");
        snippet("setting color", "A colour setting (0xRRGGBB or 0xAARRGGBB). Reads as a number.", "setting color tint = 0xFF8800\n");
        snippet("setting ... if", "Show a setting only when a condition holds, e.g. if \"enabled\" or if \"mode:Body\".", " if \"enabled\"");
        snippet("var", "Module-level variable, reset when enabled.", "var ticks = 0\n");
        snippet("fn", "Your own function. 'return value' gives a result.", "fn clampHealth(h) {\n  return clamp(h, 0, 20)\n}\n");
        snippet("after", "Run a block later (ms). It sees the variables as they were when scheduled.", "after 100 {\n  \n}\n");
        snippet("on every", "Repeat every N ms (frame precision).", "on every(250) {\n  \n}\n");
        snippet("if", "Branch. Use and / or / not.", "if  {\n  \n}\n");
        snippet("for", "Counted loop over a range.", "for i in 0..3 {\n  \n}\n");
        snippet("for in list", "Loop over a list (or a record's keys, or a string's characters).", "for p in enemies(8) {\n  \n}\n");
        snippet("while", "Loop while a condition holds.", "while  {\n  \n}\n");
        snippet("let", "Local variable.", "let x = ");
        snippet("list", "Lists: [1, 2, 3], xs[0], xs[0] = 5, push(xs, v), len(xs).", "let xs = []\n");
        snippet("ev.field", "Event values and entities have fields: ev.player.name, ev.attacker.health, target.distance.", "ev.");

        String ev = "Events";
        for (interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptEventCatalog.EventInfo info
                : interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptEventCatalog.all().values()) {
            boolean lifecycle = interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptEventCatalog.isLifecycle(info.name());
            String doc = info.fields().isEmpty() ? info.doc() : info.doc() + " Fields: " + String.join(", ", info.fields()) + ".";
            String response = interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptEventCatalog.RESPONSES.get(info.name());
            if (response != null) doc += " Response: " + response + ".";
            String insert = info.name().equals("every") ? "on every(250) {\n  \n}\n"
                    : lifecycle ? "on " + info.name() + " {\n  \n}\n"
                    : "on " + info.name() + "(ev) {\n  \n}\n";
            entry(ev, "on " + info.name(), doc, insert);
        }

        String fields = "Entity fields";
        entry(fields, "e.name / e.type / e.id / e.uuid", "Identity.", ".name");
        entry(fields, "e.x / e.y / e.z / e.eyeY", "Position.", ".x");
        entry(fields, "e.yaw / e.pitch", "Facing.", ".yaw");
        entry(fields, "e.velX / e.velY / e.velZ / e.speed", "Motion (speed is horizontal).", ".speed");
        entry(fields, "e.distance", "Distance from you.", ".distance");
        entry(fields, "e.health / e.maxHealth / e.absorption / e.armor / e.hurtTime", "Living entities only.", ".health");
        entry(fields, "e.blocking / e.using / e.activeItem", "Shield and item use.", ".blocking");
        entry(fields, "e.held / e.offhand", "Item ids in each hand.", ".held");
        entry(fields, "e.sneaking / e.sprinting / e.onGround / e.swinging", "Movement state.", ".sprinting");
        entry(fields, "e.inWater / e.inLava / e.onFire / e.invisible / e.fallDistance", "Environment.", ".onGround");
        entry(fields, "e.isPlayer / e.isSelf / e.isFriend / e.isEnemy / e.alive", "Classification.", ".isPlayer");
        entry(fields, "e.owner", "Owner of a projectile (pearls).", ".owner");

        String in = "Input";
        fn(in, "attack()", "Tap attack. Paced to once per tick across all scripts.");
        fn(in, "attackHold(on)", "Hold or release the attack key.");
        fn(in, "use()", "Tap use. Paced.");
        fn(in, "useHold(on)", "Hold or release the use key.");
        fn(in, "click(button)", "Click a mouse button (0-7). Paced.");
        fn(in, "jump()", "Jump.");
        fn(in, "sneak(on)", "Hold or release sneak.");
        fn(in, "sprint(on)", "Hold or release sprint.");
        fn(in, "keyPress(code)", "Press a key by GLFW code.");
        fn(in, "keyRelease(code)", "Release a key by GLFW code.");
        fn(in, "keyTap(code)", "Press and release a key.");
        fn(in, "swapToSlot(slot)", "Select hotbar slot 0-8.");
        fn(in, "tapSlot(slot)", "Tap a hotbar slot key (0-8).");
        fn(in, "swapToOffhand(containerSlot)", "Move a container slot to the offhand.");
        fn(in, "swapHands()", "Swap main hand and offhand.");
        fn(in, "clickSlot(containerSlot)", "Click an inventory slot.");
        fn(in, "dropItem()", "Drop the held item.");
        fn(in, "dropSlot(containerSlot)", "Drop an inventory slot.");

        String aim = "Aim";
        fn(aim, "aimAtEntity(e, point)", "Aim at an entity. point 0 = body, 1 = head. Uses the module's 'strength' setting if it has one.");
        fn(aim, "silentAimEntity(e, point)", "Aim at an entity without moving your camera.");
        fn(aim, "aimAt(x, y, z, strength)", "Aim at a point. Strength 0-100.");
        fn(aim, "setRotation(yaw, pitch, strength, silent)", "Turn to an exact rotation. Strength 0 snaps.");
        fn(aim, "clearAim()", "Release aim control.");
        fn(aim, "aimDone(tolerance)", "True when the aim is within tolerance degrees.");
        fn(aim, "yawTo(e)", "Yaw needed to face an entity.");
        fn(aim, "pitchTo(e)", "Pitch needed to face an entity.");

        String q = "Queries";
        fn(q, "nearestEnemy(range)", "Nearest non-friend player within range, or null.");
        fn(q, "lockedTarget()", "The current locked target, or null.");
        fn(q, "crosshairEntity()", "The entity under the crosshair, or null.");
        fn(q, "health()", "Your health.");
        fn(q, "maxHealth()", "Your maximum health.");
        fn(q, "food()", "Your food level.");
        fn(q, "absorption()", "Your absorption hearts.");
        fn(q, "posX()", "Your X position.");
        fn(q, "posY()", "Your Y position.");
        fn(q, "posZ()", "Your Z position.");
        fn(q, "velX()", "Your X velocity.");
        fn(q, "velY()", "Your Y velocity.");
        fn(q, "velZ()", "Your Z velocity.");
        fn(q, "yaw()", "Your yaw.");
        fn(q, "pitch()", "Your pitch.");
        fn(q, "onGround()", "True when standing on the ground.");
        fn(q, "isUsing()", "True while using an item.");
        fn(q, "isBlocking()", "True while blocking with a shield.");
        fn(q, "inGui()", "True while a screen is open.");
        fn(q, "heldItem()", "Held item id (path only, e.g. \"mace\"), \"air\" if empty.");
        fn(q, "offhandItem()", "Offhand item id.");
        fn(q, "offhandIsTotem()", "True when a totem is in the offhand.");
        fn(q, "isHoldingWeapon()", "True when holding a weapon.");
        fn(q, "findItemSlot(text)", "Inventory index (0-35) of an item matching text, or -1.");
        fn(q, "slotCount(text)", "How many items match text.");
        fn(q, "selectedSlot()", "Selected hotbar slot 0-8.");
        fn(q, "distanceTo(e)", "Distance to an entity.");
        fn(q, "entHealth(e)", "An entity's health.");
        fn(q, "entX(e)", "An entity's X.");
        fn(q, "entY(e)", "An entity's Y.");
        fn(q, "entZ(e)", "An entity's Z.");
        fn(q, "entIsPlayer(e)", "True when the entity is a player.");
        fn(q, "entName(e)", "An entity's name.");
        fn(q, "attackReady(adjustTicks)", "True when the attack cooldown allows a full hit.");

        String world = "World";
        fn(world, "getBlock(x, y, z)", "Block id at a position, \"air\" if empty.");
        fn(world, "isSolid(x, y, z)", "True when the block is solid.");
        fn(world, "placeBlock(x, y, z)", "Place a block (6 block range, paced, validated). Returns true on success.");

        String util = "Util";
        fn(util, "rand(lo, hi)", "Random number between lo and hi.");
        fn(util, "randInt(lo, hi)", "Random whole number between lo and hi.");
        fn(util, "now()", "Milliseconds since the epoch.");
        fn(util, "print(x)", "Show a chat message (limited to one per 500 ms).");
        fn(util, "min(a, b)", "Smaller of two numbers.");
        fn(util, "max(a, b)", "Larger of two numbers.");
        fn(util, "abs(x)", "Absolute value.");
        fn(util, "floor(x)", "Round down.");
        fn(util, "ceil(x)", "Round up.");
        fn(util, "round(x)", "Round to nearest.");
        fn(util, "sqrt(x)", "Square root.");
        fn(util, "sin(x)", "Sine.");
        fn(util, "cos(x)", "Cosine.");
        fn(util, "atan2(y, x)", "Arc tangent of y / x.");
        fn(util, "clamp(v, lo, hi)", "Limit v to the range lo..hi.");
        fn(util, "lerp(t, a, b)", "Blend from a to b by t.");
        fn(util, "pow(x, y)", "x to the power y.");
        fn(util, "tan(x)", "Tangent.");
        fn(util, "toRadians(deg)", "Degrees to radians.");
        fn(util, "toDegrees(rad)", "Radians to degrees.");
        fn(util, "wrapDegrees(deg)", "Wrap an angle into -180..180.");
        fn(util, "dist3(x1, y1, z1, x2, y2, z2)", "Distance between two points.");
        fn(util, "notify(title, message)", "Show an Apexx notification card.");

        String timing = "Timing";
        fn(timing, "gauss(mean, stdev)", "Gaussian random number (truncated, never below 0). Use for human-looking delays.");
        fn(timing, "sample(settingName)", "A slider setting's value with its 'jitter' StDev applied.");
        fn(timing, "chance(percent)", "True with the given probability (0-100).");
        fn(timing, "pick(list)", "A random element of a list.");
        fn(timing, "uptime()", "Milliseconds since this script was enabled.");
        fn(timing, "ticks()", "Your player's age in ticks.");

        String data = "Lists & text";
        fn(data, "len(x)", "Length of a list, string or record.");
        fn(data, "push(list, v)", "Append to a list (returns the list).");
        fn(data, "pop(list)", "Remove and return the last element.");
        fn(data, "insert(list, i, v)", "Insert at an index.");
        fn(data, "removeAt(list, i)", "Remove and return the element at i (negative counts from the end).");
        fn(data, "clear(list)", "Empty a list.");
        fn(data, "contains(x, v)", "List has v / string contains v / record has key v.");
        fn(data, "indexOf(x, v)", "Position of v in a list or string, or -1.");
        fn(data, "join(list, sep)", "Join a list into text.");
        fn(data, "range(lo, hi)", "A list of the numbers lo..hi-1.");
        fn(data, "slice(list, from, to)", "Part of a list.");
        fn(data, "sort(list)", "A sorted copy (numbers or text).");
        fn(data, "keys(record)", "Field names of a record (e.g. an event).");
        fn(data, "get(x, key)", "x[key] for lists, records and entities.");
        fn(data, "str(x)", "Text form of a value.");
        fn(data, "num(x)", "Number form of a value (parses text).");
        fn(data, "typeOf(x)", "\"number\", \"string\", \"bool\", \"list\", \"record\", \"entity\" or \"null\".");
        fn(data, "lower(s)", "Lower case.");
        fn(data, "upper(s)", "Upper case.");
        fn(data, "trim(s)", "Strip surrounding spaces.");
        fn(data, "startsWith(s, prefix)", "Text starts with prefix.");
        fn(data, "endsWith(s, suffix)", "Text ends with suffix.");
        fn(data, "replace(s, from, to)", "Replace every occurrence.");
        fn(data, "split(s, sep)", "Split text into a list.");
        fn(data, "substring(s, from, to)", "Part of a text.");

        String ents = "Entities";
        fn(ents, "self()", "Your own player as an entity (self().health, self().x ...).");
        fn(ents, "players(range)", "Other players within range, nearest first.");
        fn(ents, "enemies(range)", "Non-friend players within range, nearest first.");
        fn(ents, "entities(range, type)", "Entities within range; type filters by id text (\"\" for all). Max 256.");
        fn(ents, "isFriend(e)", "True for a player on your friends list.");
        fn(ents, "canSee(e)", "True when you have line of sight to e.");
        fn(ents, "hasEffect(e, name)", "True if e has a status effect, e.g. \"slowness\".");
        fn(ents, "effectLevel(e, name)", "Effect level (1 = level I), 0 if absent.");
        fn(ents, "ping()", "Your latency in ms.");
        fn(ents, "hotbarSlotOf(text)", "Hotbar slot 0-8 of an item matching text, or -1.");
        fn(ents, "crosshairBlock()", "The block you are looking at as {x, y, z, block, face}, or null.");

        String act = "Actions";
        fn(act, "selectSlot(slot)", "Select hotbar slot 0-8 and sync it with the server (the shared SlotSync path).");
        fn(act, "swapBack(slot)", "Return to a slot at the start of the next interval (never in the same frame as an action).");
        fn(act, "attackEntity(e)", "Attack an entity through AttackValidator (reach, cooldown and live aim checks). Paced.");
        fn(act, "hitCrosshair()", "Attack what the crosshair is on through the validated click path. Paced.");
        fn(act, "useItem()", "Use the held item through the click controller (honours Legit/Direct mode). Paced.");
        fn(act, "bindHeld()", "True while this script's keybind is held.");
        fn(act, "keyCode(name)", "Key code for a name like \"R\" or \"MOUSE4\" (-1 if unknown).");
        fn(act, "keyName(code)", "Name of a key code.");
        fn(act, "isKeyDown(key)", "True while a key (code or name) is held and no screen is open.");
        fn(act, "disableScript()", "Turn this script off after the current handler.");
        fn(act, "cancel(kind)", "In attack_input, use_input, block_interact or swap_lock: cancel it. Kind is optional (\"swap\"/\"attack\" for swap_lock).");
        fn(act, "setResult(v)", "In jump_input (true = jump) or mouse_sensitivity (0..1): answer the hook.");
        fn(act, "setInput(name, on)", "In movement_input: set forward/backward/left/right/jump/sneak/sprint for this tick.");
        fn(act, "setLook(dx, dy)", "In look_input: replace this mouse movement, in degrees.");

        String draw = "HUD drawing";
        fn(draw, "drawText(text, x, y, color, shadow)", "Draw text on the HUD. Only inside 'on render'. color and shadow are optional (white, no shadow). True if drawn.");
        fn(draw, "drawRect(x, y, w, h, color)", "Fill a rectangle. Only inside 'on render'.");
        fn(draw, "drawOutline(x, y, w, h, color, thickness)", "Rectangle outline; thickness 1-16 (optional, 1). Only inside 'on render'.");
        fn(draw, "drawLine(x1, y1, x2, y2, color, thickness)", "A straight line; thickness 1-16 (optional, 1). Only inside 'on render'.");
        fn(draw, "textWidth(text)", "Width of text in HUD pixels.");
        fn(draw, "screenWidth()", "HUD width in scaled pixels.");
        fn(draw, "screenHeight()", "HUD height in scaled pixels.");
        entry(draw, "colours", "0xRRGGBB, 0xAARRGGBB, a colour setting, or a string such as \"#FF8800\" or \"#80FF8800\". "
                + "Outside 'on render' the draw functions do nothing and return false. Each render pass allows "
                + "a few thousand draws. With SS Proof on, use category render so drawing stays off capture.", "\"#FFFFFF\"");

        String dbg = "Debugger";
        entry(dbg, "breakpoints", "Click a line number to set or clear a breakpoint, open Debug and turn it on. Each time the "
                + "script reaches that line, its variables, module vars and settings are saved as a snapshot. "
                + "Nothing pauses; step through the snapshots with Prev / Next.", "");

        String tpl = "Templates";
        for (String[] t : templates()) entry(tpl, t[0], t[1], t[2]);
    }

    /** Ready-to-use scripts: {name, description, source}. Each one is compiled by a unit test. */
    public static String[][] templates() {
        return new String[][] {
            {"Shield Crouch", "Crouch briefly when a nearby enemy raises their shield.",
                    "module \"Shield Crouch\" {\n"
                    + "  category combat\n"
                    + "  description \"Crouch briefly when a nearby enemy raises their shield\"\n"
                    + "  setting slider reach = 4 range 1..8 step 0.5\n"
                    + "  setting slider hold = 120 range 0..500 jitter 25 \"How long to crouch, ms\"\n"
                    + "\n"
                    + "  on shield_raise(ev) {\n"
                    + "    if ev.self or ev.player.isFriend { return }\n"
                    + "    if ev.player.distance > reach { return }\n"
                    + "    sneak(true)\n"
                    + "    after sample(\"hold\") {\n"
                    + "      sneak(false)\n"
                    + "    }\n"
                    + "  }\n"
                    + "}\n"},
            {"Pop Counter", "Counts enemy totem pops and shows a notification for each.",
                    "module \"Pop Counter\" {\n"
                    + "  category render\n"
                    + "  description \"Counts totem pops and announces them\"\n"
                    + "  var pops = 0\n"
                    + "\n"
                    + "  on totem_pop(ev) {\n"
                    + "    if ev.self { return }\n"
                    + "    pops = pops + 1\n"
                    + "    notify(\"Totem pop\", ev.player.name + \" popped (\" + str(pops) + \" total)\")\n"
                    + "  }\n"
                    + "}\n"},
            {"Burst Click", "A trigger script: each key press sends a short burst of validated hits.",
                    "module \"Burst Click\" {\n"
                    + "  category combat\n"
                    + "  description \"Press the key for a short burst of validated hits\"\n"
                    + "  type trigger\n"
                    + "  key \"R\"\n"
                    + "  setting slider hits = 3 range 1..5\n"
                    + "  setting slider gap = 90 range 0..300 jitter 20 \"Delay between hits, ms\"\n"
                    + "\n"
                    + "  on trigger {\n"
                    + "    for i in 0..hits {\n"
                    + "      after i * sample(\"gap\") {\n"
                    + "        hitCrosshair()\n"
                    + "      }\n"
                    + "    }\n"
                    + "  }\n"
                    + "}\n"},
            {"Low Health Alert", "Warns once when your health drops below a threshold, with a cooldown.",
                    "module \"Low Health Alert\" {\n"
                    + "  category player\n"
                    + "  description \"Warn when health drops low\"\n"
                    + "  setting slider threshold = 8 range 1..20\n"
                    + "  var lastWarn = 0\n"
                    + "\n"
                    + "  on health_change(ev) {\n"
                    + "    if ev.health < threshold and ev.delta < 0 and now() - lastWarn > 3000 {\n"
                    + "      lastWarn = now()\n"
                    + "      notify(\"Low health\", str(round(ev.health)) + \" HP left\")\n"
                    + "    }\n"
                    + "  }\n"
                    + "}\n"},
            {"Enemy Tracker", "Keeps a list of enemies who swung at you in the last 5 seconds.",
                    "module \"Enemy Tracker\" {\n"
                    + "  category combat\n"
                    + "  description \"Track recent attackers\"\n"
                    + "  var recent = []\n"
                    + "\n"
                    + "  fn remember(name) {\n"
                    + "    if not contains(recent, name) {\n"
                    + "      push(recent, name)\n"
                    + "    }\n"
                    + "  }\n"
                    + "\n"
                    + "  on hurt(ev) {\n"
                    + "    if ev.attacker and ev.attacker.isPlayer {\n"
                    + "      remember(ev.attacker.name)\n"
                    + "      let who = ev.attacker.name\n"
                    + "      after 5000 {\n"
                    + "        let i = indexOf(recent, who)\n"
                    + "        if i >= 0 { removeAt(recent, i) }\n"
                    + "      }\n"
                    + "    }\n"
                    + "  }\n"
                    + "\n"
                    + "  on every(1000) {\n"
                    + "    if len(recent) > 1 { print(\"Attackers: \" + join(recent, \", \")) }\n"
                    + "  }\n"
                    + "}\n"},
        };
    }

    public static List<Entry> entries() { return ENTRIES; }

    /** A starter script that compiles and shows the shape of a module. */
    public static String starter(String title) {
        return "# " + title + "\n"
                + "module \"" + title + "\" {\n"
                + "  category misc\n"
                + "  description \"Describe what this script does\"\n"
                + "\n"
                + "  setting slider reach = 4 range 1..6 step 0.5\n"
                + "\n"
                + "  on tick {\n"
                + "    let target = nearestEnemy(reach)\n"
                + "    if target {\n"
                + "      # your logic here\n"
                + "    }\n"
                + "  }\n"
                + "}\n";
    }
}
