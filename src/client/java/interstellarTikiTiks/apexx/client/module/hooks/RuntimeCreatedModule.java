package interstellarTikiTiks.apexx.client.module.hooks;

/**
 * Marks a {@code Module} subclass that ships in the payload but is created by other payload code at run time (for
 * example one module per user script), so the payload loader must not instantiate it as a standalone module.
 */
public interface RuntimeCreatedModule {
}
