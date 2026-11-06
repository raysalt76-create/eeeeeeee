package interstellarTikiTiks.apexx.client.module.hooks;

/**
 * Marker for a module that clicks, uses items or swaps hotbar slots for the player. The base
 * {@code Module} constructor gives every such module its own "Click Sim" toggle; when it is on,
 * that module's clicks and key presses are sent as real input events with a short visible hold,
 * so keystroke overlays and the vanilla input path see them. Off (the default) acts directly.
 * No methods to implement.
 */
public interface ClickSimulating {
}
