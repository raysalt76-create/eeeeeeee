package interstellarTikiTiks.apexx.client.module.implementation.targeteffect;

import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.BaseEffect;
import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.effects.*;

import java.util.HashMap;
import java.util.Map;

public class EffectFactory {
    private final Map<String, interstellarTikiTiks.apexx.client.module.implementation.targeteffect.BaseEffect> effectCache = new HashMap<>();

    public EffectFactory() {
        effectCache.put("Spinning Spheres", new SpinningSpheresEffect());
        effectCache.put("Orbit Ring", new OrbitRingEffect());
        effectCache.put("Pulse", new PulseEffect());
        effectCache.put("Helix", new HelixEffect());
        effectCache.put("Galaxy", new GalaxyEffect());
        effectCache.put("Tornado", new TornadoEffect());
        effectCache.put("Rings", new RingsEffect());
        effectCache.put("Spiral", new SpiralEffect());
        effectCache.put("Lightning", new LightningCageEffect());
        effectCache.put("Shockwave", new ShockwaveRingsEffect());
        effectCache.put("Scanlines", new ScanLinesEffect());
        effectCache.put("Goofball", new CombinationEffect());
        effectCache.put("Overlay", new OverlayEffect());
    }

    public BaseEffect getEffect(String name) {
        return effectCache.getOrDefault(name, effectCache.get("Spinning Spheres"));
    }
}
