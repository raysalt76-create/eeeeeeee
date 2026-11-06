package interstellarTikiTiks.apexx.client.module.hooks;

/**
 * Lets a free-tier module check whether a premium-only crossbow/cart combo module is mid-sequence,
 * without holding a hard class reference to the premium module itself (the free payload build never
 * ships that class). Query via {@code ModuleAccess.getEnabledHook(CartSequenceAware.class)}.
 */
public interface CartSequenceAware {

    boolean isSequenceActive();
}
