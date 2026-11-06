package interstellarTikiTiks.apexx.client.module;

public enum Category {
    COMBAT("Combat"),
    MOVEMENT("Movement"),
    // Key-triggered item automations: hold or tap a bind, the client swaps to the item, uses it and swaps back.
    AUTOMATIONS("Automations"),
    RENDER("Render"),
    PLAYER("Player"),
    FRIENDS("Friends"),
    ENEMIES("Enemies"),
    MISC("Misc"),
    // Modules that are flatly impossible in vanilla and make no attempt to hide it -- raw packet
    // rates, removed cooldowns, extended reach. Grouped so it is obvious at a glance what will get
    // you banned on a server that checks. Nothing in here is humanised and nothing in here is safe.
    BLATANT("Blatant"),
    // Utility modules for survival multiplayer (SMP) servers only. They are written for servers that run
    // no movement or combat checks, so they use plain vanilla calls and make no attempt to look legit.
    SMP("SMP"),
    // Bedwars-specific modules (bridging, block/soup/head automation, click assists). New and marked unstable in the menu.
    BEDWARS("Bedwars"),
    CLIENT("Client");

    public final String name;

    Category(String name) {
        this.name = name;
    }
}
