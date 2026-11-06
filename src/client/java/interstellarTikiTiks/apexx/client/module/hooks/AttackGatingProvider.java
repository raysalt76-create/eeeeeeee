package interstellarTikiTiks.apexx.client.module.hooks;

public interface AttackGatingProvider {

    /**
     * Whether manual attack input (physical mouse left-click and attack keybinding)
     * should be gated/suppressed from direct vanilla swinging and keystroke HUD display.
     */
    boolean shouldGateAttackInput();
}
