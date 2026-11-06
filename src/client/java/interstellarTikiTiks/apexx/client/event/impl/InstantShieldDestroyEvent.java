package interstellarTikiTiks.apexx.client.event.impl;

import interstellarTikiTiks.apexx.client.event.Event;

/**
 * Fire-and-forget marker posted the instant RapptureShieldDrain finishes a
 * burst of mace clicks against a blocking-shield target — signalling that the
 * shield was just popped so a downstream mace-smash module can capitalize.
 *
 * This is intentionally NOT the same contract as {@link ShieldDrainEvent}
 * (which is a queryable "a drain is running, suppress your swing" event).
 * Consumers register an @EventTarget and react; there is no state to read.
 */
public class InstantShieldDestroyEvent implements Event {
}
