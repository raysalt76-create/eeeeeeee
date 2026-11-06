package interstellarTikiTiks.apexx.client.module.implementation.blatant;

import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.implementation.movement.AutoLunge;

/**
 * InstantLunge - the Blatant variant of AutoLunge (formerly its "Blatant" mode). It runs the same spear
 * selection, aim assist, stop band and hold-to-repeat machinery, but lunges at any attack-charge level and
 * with near-zero gaps between the spear swap, the attack and the swap-back. No player can lunge that fast
 * and servers that check attack charge or swap timing flag it. Off by default like every Blatant module.
 */
public class InstantLunge extends AutoLunge {

    public InstantLunge() {
        super("InstantLunge", "BLATANT: lunges with your Lunge spear instantly at any charge level and repeats as fast as the Repeat Speed allows, with near-zero swap gaps. Much faster than any player can lunge; servers that check attack charge or swap timing will flag it.", Category.BLATANT, true);
    }
}
