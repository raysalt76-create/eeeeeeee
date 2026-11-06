package interstellarTikiTiks.apexx.client.module.hooks;

public interface MovementInputOverrideProvider {

    boolean isOverridingSnapTap();

    boolean isSuppressingForward();

    boolean isStapMode();
}
