package interstellarTikiTiks.apexx.client.cosmetics;

import interstellarTikiTiks.apexx.client.ApexxAuth;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.CosmeticsView;
import interstellarTikiTiks.apexx.client.module.hooks.CosmeticsProvider;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.util.Identifier;

import java.util.function.Consumer;

/**
 * Loader-side access for the Cosmetics page: the choices live in the cloud prefs, everything else goes through the
 * premium-payload {@link CosmeticsProvider}. Without it (free payload) or without a premium session the page is locked.
 */
public final class CosmeticsBackend implements CosmeticsView.Backend {
    private static CosmeticsProvider provider() {
        return ModuleAccess.getHook(CosmeticsProvider.class);
    }

    @Override
    public boolean unlocked() {
        CosmeticsProvider p = provider();
        return p != null && p.cosmeticsAvailable();
    }

    @Override
    public CosmeticPrefs prefs() {
        return CosmeticPrefs.get();
    }

    @Override
    public Identifier capeTexture(CapeDesign design) {
        CosmeticsProvider p = provider();
        return p == null ? null : p.capeTexture(design);
    }

    @Override
    public int accentRgb() {
        return AccentState.accentArgb() & 0xFFFFFF;
    }

    @Override
    public boolean previewAvailable() {
        CosmeticsProvider p = provider();
        return p != null && p.previewAvailable();
    }

    @Override
    public boolean signedIn() {
        return ApexxAuth.getSessionToken() != null;
    }

    @Override
    public String syncStatus() {
        CosmeticsProvider p = provider();
        return p == null ? "" : p.syncStatus();
    }

    @Override
    public void importImage(Consumer<String> onEncoded, Consumer<String> onError) {
        CosmeticsProvider p = provider();
        if (p == null) onError.accept("Cosmetics are a Premium feature.");
        else p.importCapeImage(onEncoded, onError);
    }
}
