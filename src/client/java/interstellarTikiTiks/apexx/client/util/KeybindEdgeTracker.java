package interstellarTikiTiks.apexx.client.util;

import java.util.IdentityHashMap;

/**
 * Edge-detect state for polled keybinds, keyed by the bind's owner object (a module, a setting, or a sentinel)
 * plus its key codes. A rebind (different codes) starts from "not pressed", like the previous
 * {@code name + ":" + key + ":" + key2} string keys, but the per-tick poll no longer builds a String and boxes a
 * Boolean for every bound module and setting. Client thread only.
 */
public final class KeybindEdgeTracker {
    private static final class Edge {
        int key1;
        int key2;
        boolean down;
    }

    private final IdentityHashMap<Object, Edge> edges = new IdentityHashMap<>();

    /** Whether the bind {@code owner} with these codes was down at its last update. */
    public boolean wasDown(Object owner, int key1, int key2) {
        Edge edge = edges.get(owner);
        return edge != null && edge.down && edge.key1 == key1 && edge.key2 == key2;
    }

    public void update(Object owner, int key1, int key2, boolean down) {
        Edge edge = edges.get(owner);
        if (edge == null) {
            edge = new Edge();
            edges.put(owner, edge);
        }
        edge.key1 = key1;
        edge.key2 = key2;
        edge.down = down;
    }

    public void clear() {
        edges.clear();
    }
}
