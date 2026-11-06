
package interstellarTikiTiks.apexx.client.module.hooks;

/**
 * Lets an enabled module block vanilla right-click use. Mixins live in the loader and
 * cannot reference payload module classes directly, so they query this hook instead.
 */
public interface ItemUseBlockProvider {

    /** @return true to cancel the current vanilla {@code doItemUse}. */
    boolean shouldBlockItemUse();
}
