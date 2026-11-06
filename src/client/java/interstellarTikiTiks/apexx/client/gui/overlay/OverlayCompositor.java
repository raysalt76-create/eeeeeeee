package interstellarTikiTiks.apexx.client.gui.overlay;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.hud.HUDElement;
import interstellarTikiTiks.apexx.client.hud.HUDManager;
import interstellarTikiTiks.apexx.client.manager.ModuleManager;
import interstellarTikiTiks.apexx.client.mixin.GameRendererAccessor;
import interstellarTikiTiks.apexx.client.mixin.GuiRendererAccessor;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.NotificationSink;
import interstellarTikiTiks.apexx.client.module.hooks.SsProofProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.Notifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.texture.GlTexture;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Redirects Minecraft's GUI flush into an offscreen framebuffer while an Apexx screen is open, and
 * presents that buffer onto the transparent, capture-excluded {@link OverlayWindow}.
 *
 * <p>{@code GuiRenderer.render} can flush more than once per real frame, so the redirect decision
 * ({@link #beginFrame()}/{@link #guiTarget}/{@link #endFrame()}) is stateless and re-evaluated on
 * every flush, exactly like the proven in-process overlay this was ported from - it does not try to
 * split "vanilla HUD" and "client interface" into two separately tracked phases of one frame, which
 * is fragile against however many times the renderer actually flushes. Once-per-real-frame concerns
 * (capture-exclusion policy, fail-open, notices, window show/hide) live separately in
 * {@link #frameTick()}, called once from {@code GameRendererMixin}.
 */
public final class OverlayCompositor {
    private OverlayCompositor() {}

    private static SimpleFramebuffer fbo;
    private static int fbW = 0;
    private static int fbH = 0;
    private static boolean activeThisFrame = false;
    private static boolean worldVisualActiveThisFrame = false;
    private static boolean fboClearedThisFrame = false;
    private static boolean hudFlushActive = false;
    private static boolean contentDrawnThisFrame = false;

    // Resolved once per real frame in frameTick(); beginFrame()/endFrame() only read this.
    private static SsProofPolicy.State currentState = SsProofPolicy.State.OFF;
    private static long pendingSinceMs = 0L;
    private static int consecutiveAffinityFailures = 0;
    private static SsProofPolicy.Reason lastNoticeReason = SsProofPolicy.Reason.NONE;

    // --------------------------------------------------------------------------------------------
    // State queries
    // --------------------------------------------------------------------------------------------

    public static boolean isSsProofEnabled() {
        // The overlay is its own GLFW window; an Android launcher has no such window.
        if (interstellarTikiTiks.apexx.client.util.MobilePlatform.isMobile()) return false;
        try {
            SsProofProvider provider = ModuleAccess.getHook(SsProofProvider.class);
            // On Wayland the provider already reports off (ClickGUIModule.isSsProof): a Wayland compositor gives no
            // application a way to place or exclude a separate overlay window, so it is never started there.
            return provider != null && provider.isSsProofEnabled()
                    && !interstellarTikiTiks.apexx.client.util.GlfwPlatform.isWayland();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static volatile boolean waylandNoticeShown;

    /** One toast per run telling a Wayland user why SS Proof is off. Safe from any thread. */
    private static boolean unsupportedNoticeShown;

    /**
     * Capture exclusion only exists on Windows. Elsewhere (macOS, Linux, mobile launchers) SS Proof runs as off:
     * redirecting would hide the menu, cursor and visuals for good, and opening the second native window can crash a
     * mobile GLFW emulation. The saved setting is left alone so the same config still works on a Windows PC.
     */
    public static void noteUnsupportedPlatform() {
        if (unsupportedNoticeShown) return;
        unsupportedNoticeShown = true;
        notice("SS Proof unavailable", SsProofPolicy.noticeText(SsProofPolicy.Reason.UNSUPPORTED_PLATFORM));
    }

    public static void noteWaylandUnavailable() {
        if (waylandNoticeShown) return;
        waylandNoticeShown = true;
        notice("SS Proof unavailable on Wayland", "The interface is drawn normally. Use an X11 session for SS Proof.");
    }

    /**
     * True only once the overlay's own capture exclusion has been verified (read back via
     * GetWindowDisplayAffinity, not just assumed from SetWindowDisplayAffinity's return value).
     * Callers that gate a capture-sensitive behaviour on this (hiding the system cursor, redirecting
     * chat to the overlay) must not act as if content is hidden before that is actually true.
     */
    public static boolean isSsProofActive() {
        return currentState == SsProofPolicy.State.READY;
    }

    public static boolean isSsProofReady() {
        return isSsProofActive();
    }

    /**
     * True whenever this frame's SS Proof state is anything but OFF (starting up or ready). While it is, client
     * draws must only reach a redirected flush ({@link #shouldRedirect()} / {@link #shouldRedirectHud()}).
     */
    public static boolean isSsProofOn() {
        return currentState != SsProofPolicy.State.OFF;
    }

    /** True while the current GUI flush is being redirected into the overlay framebuffer. */
    public static boolean isOverlayCaptureActive() {
        return activeThisFrame;
    }

    /**
     * Every screen the client opens must stay off capture, not just the ClickGUI. Uses the same
     * package-prefix check as {@code ClickGUIModule}'s write guard (any screen under this class's own
     * package tree, {@code interstellarTikiTiks.apexx.client.gui.*}), read reflectively at runtime via
     * {@code getPackageName()} so it still resolves correctly after obfuscation renames the package -
     * a hardcoded list of screen classes here would silently stop covering a new Apexx screen the
     * moment one is added, exactly the kind of gap that caused the original capture leak this session.
     */
    private static boolean isApexxScreen(Screen screen) {
        return SsProofPolicy.isApexxScreenClass(screen.getClass().getName(), OverlayCompositor.class.getPackageName());
    }

    /**
     * Whether this GUI flush should be redirected into the overlay framebuffer. Gated on
     * {@code currentState != OFF} only, NOT on {@code READY}: the moment SS Proof is enabled, client
     * draws must never reach the capturable game framebuffer, even while the overlay window is still
     * starting up or its exclusion is not yet verified. {@link #endFrame()} is what decides, from the
     * same resolved state, whether the redirected content is actually presented to the player.
     */
    public static boolean shouldRedirect() {
        if (currentState == SsProofPolicy.State.OFF) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.currentScreen != null && isApexxScreen(mc.currentScreen);
    }

    /** Any enabled Category.RENDER module whose visuals should be kept off capture. */
    private static boolean hasActiveRenderVisuals() {
        Apexx apexx = Apexx.getInstance();
        ModuleManager mm = apexx != null ? apexx.getModuleManager() : null;
        if (mm == null) return false;
        for (Module m : mm.getModules()) {
            if (!m.isEnabled()) continue;
            if (m.getCategory() != interstellarTikiTiks.apexx.client.module.Category.RENDER) continue;
            String name = m.getName();
            if ("FullBright".equalsIgnoreCase(name)) continue;
            if ("Ambience".equalsIgnoreCase(name)) continue;
            return true;
        }
        return false;
    }

    /** Any enabled standalone HUD element (watermark, keystrokes, ...) whose visuals should be kept off capture. */
    private static boolean hasActiveHudVisuals() {
        HUDManager hud = HUDManager.getInstance();
        if (hud == null) return false;
        for (HUDElement el : hud.getElements()) {
            if (el.isEnabled()) return true;
        }
        return false;
    }

    /**
     * Whether standalone HUD elements and screen-space Category.RENDER module visuals (Nametags etc.)
     * should be redirected this frame. Only applies with no Apexx screen open - that case is already
     * covered by {@link #shouldRedirect()}/{@link #beginFrame()} - so the two paths never both fire for
     * the same content.
     */
    public static boolean shouldRedirectHud() {
        if (currentState == SsProofPolicy.State.OFF) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return false;
        // An Apexx screen is already redirected whole by shouldRedirect(); every other screen (chat, inventory,
        // pause) still has module visuals drawn under it, which must stay off the capturable frame too.
        if (mc.currentScreen != null && isApexxScreen(mc.currentScreen)) return false;
        return hasActiveRenderVisuals() || hasActiveHudVisuals();
    }

    // --------------------------------------------------------------------------------------------
    // Once-per-real-frame policy (driven by GameRendererMixin)
    // --------------------------------------------------------------------------------------------

    /**
     * HEAD of GameRenderer.render: resolves this frame's {@link SsProofPolicy.State} (with the
     * failure-streak counting, opt-in fail-open recovery and one-time notices that must happen
     * exactly once per frame) and keeps the overlay window started/stopped accordingly.
     */
    public static void frameTick() {
        fboClearedThisFrame = false;
        contentDrawnThisFrame = false;
        currentState = resolveState();
        if (currentState == SsProofPolicy.State.OFF) {
            if (OverlayWindow.isShown()) {
                OverlayWindow.hide();
            }
            return;
        }
        OverlayWindow.ensureCreated();
    }

    private static SsProofPolicy.State resolveState() {
        if (!isSsProofEnabled()) {
            pendingSinceMs = 0L;
            consecutiveAffinityFailures = 0;
            lastNoticeReason = SsProofPolicy.Reason.NONE;
            return SsProofPolicy.State.OFF;
        }

        boolean supportedPlatform = SsProofPolicy.isSupportedPlatform(System.getProperty("os.name", ""));
        boolean processAlive = OverlayWindow.isAvailable();
        int affinity = processAlive ? OverlayWindow.affinityState()
                : OverlayWindow.creationGaveUp() ? SsProofPolicy.AFFINITY_FAILED : SsProofPolicy.AFFINITY_UNKNOWN;

        if (affinity == SsProofPolicy.AFFINITY_VERIFIED) {
            consecutiveAffinityFailures = 0;
        } else if (affinity == SsProofPolicy.AFFINITY_FAILED) {
            consecutiveAffinityFailures++;
        }

        SsProofPolicy.State state = SsProofPolicy.resolve(true, supportedPlatform, processAlive, affinity);
        long now = System.currentTimeMillis();
        if (state == SsProofPolicy.State.PENDING) {
            if (pendingSinceMs == 0L) pendingSinceMs = now;
        } else {
            pendingSinceMs = 0L;
        }

        // Opt-in recovery: give up hiding and turn SS Proof off (one-time, capturable notice) rather
        // than leaving the interface invisible forever on a system where exclusion never verifies.
        if (interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule.isSsProofFailOpen()
                && SsProofPolicy.shouldFailOpen(true, consecutiveAffinityFailures)) {
            interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule.disableSsProofForFailOpen();
            showNotice(SsProofPolicy.failOpenNoticeText());
            consecutiveAffinityFailures = 0;
            lastNoticeReason = SsProofPolicy.Reason.NONE;
            return SsProofPolicy.State.OFF;
        }

        SsProofPolicy.Reason reason = SsProofPolicy.reason(state, supportedPlatform, affinity, pendingSinceMs, now);
        if (reason != SsProofPolicy.Reason.NONE) {
            if (reason != lastNoticeReason) {
                lastNoticeReason = reason;
                showNotice(SsProofPolicy.noticeText(reason));
            }
        } else {
            lastNoticeReason = SsProofPolicy.Reason.NONE;
        }

        return state;
    }

    /** One-time (per reason change) notice explaining why the client interface is being held back. */
    private static void showNotice(String text) {
        if (text == null || text.isEmpty()) return;
        notice("SS Proof", text);
    }

    /**
     * Shows a notice through Apexx's own styled notification cards when that system is available
     * (the "Notifications" module is enabled), falling back to vanilla's system toast only when it
     * isn't - these SS Proof notices are safety-relevant and must never be silently dropped just
     * because an unrelated cosmetic toggle happens to be off.
     */
    private static void notice(String title, String text) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return;
            mc.execute(() -> {
                if (ModuleAccess.getEnabledHook(NotificationSink.class) != null) {
                    Notifier.warn(title, text);
                } else {
                    net.minecraft.client.toast.SystemToast.show(mc.getToastManager(),
                            net.minecraft.client.toast.SystemToast.Type.PERIODIC_NOTIFICATION,
                            net.minecraft.text.Text.literal(title),
                            net.minecraft.text.Text.literal(text));
                }
            });
        } catch (Throwable ignored) {
            // A notice is best effort.
        }
    }

    // --------------------------------------------------------------------------------------------
    // World-space visuals (Category.RENDER modules) -> overlay framebuffer
    // --------------------------------------------------------------------------------------------

    /**
     * Redirects world-space render-category module visuals (ESP, trajectories, ...) into the overlay
     * framebuffer instead of the game framebuffer. Runs during world render, strictly before the GUI
     * flush later in the same frame, so it shares one clear-once-per-frame buffer with
     * {@link #beginFrame()} instead of wiping it: whichever of the two runs first for a given frame
     * clears the buffer, the other layers its own draws on top.
     */
    public static void beginWorldRender() {
        worldVisualActiveThisFrame = false;
        if (currentState == SsProofPolicy.State.OFF || !hasActiveRenderVisuals()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        Framebuffer main = mc.getFramebuffer();
        if (main == null) return;
        int w = main.textureWidth;
        int h = main.textureHeight;
        if (w <= 0 || h <= 0) return;
        if (!ensureFbo(w, h)) return;
        if (!fboClearedThisFrame) {
            clearFbo();
            fboClearedThisFrame = true;
        }

        try {
            RenderSystem.outputColorTextureOverride = fbo.getColorAttachmentView();
            RenderSystem.outputDepthTextureOverride = main.getDepthAttachmentView();
            worldVisualActiveThisFrame = true;
            contentDrawnThisFrame = true;
        } catch (Throwable t) {
            worldVisualActiveThisFrame = false;
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
        }
    }

    public static void endWorldRender() {
        if (!worldVisualActiveThisFrame) return;
        try {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
        } catch (Throwable ignored) {
        }
    }

    // --------------------------------------------------------------------------------------------
    // Standalone HUD elements & screen-space Render visuals -> overlay framebuffer
    // --------------------------------------------------------------------------------------------

    /**
     * Called from {@code InGameHudMixin} HEAD, before vanilla's own hotbar/hunger/XP bar queue
     * anything into the shared GUI render state. Draws Apexx's own HUD elements and screen-space
     * Category.RENDER module visuals into that same state, then immediately force-flushes just that
     * content - redirected into the overlay framebuffer via {@link #guiTarget} - so the state is
     * clean again before vanilla's own draws queue moments later in the very same
     * {@code InGameHud.render} call. Vanilla's subsequent draws then flush normally, at the real end
     * of the frame, untouched and fully visible on the game framebuffer.
     */
    public static void renderAndFlushHudEarly(DrawContext context, float tickDelta) {
        if (!shouldRedirectHud()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.gameRenderer == null) return;
        Framebuffer main = mc.getFramebuffer();
        if (main == null) return;
        int w = main.textureWidth;
        int h = main.textureHeight;
        if (w <= 0 || h <= 0) return;
        if (!ensureFbo(w, h)) return;

        try {
            Apexx apexx = Apexx.getInstance();
            if (apexx != null) {
                apexx.getModuleManager().onRender(context, tickDelta);
            }
            HUDManager hud = HUDManager.getInstance();
            if (hud != null && mc.currentScreen == null) {
                hud.render(context, tickDelta);
            }
        } catch (Throwable t) {
            return;
        }

        if (!fboClearedThisFrame) {
            clearFbo();
            fboClearedThisFrame = true;
        }

        hudFlushActive = true;
        try {
            GameRendererAccessor accessor = (GameRendererAccessor) mc.gameRenderer;
            FogRenderer fog = accessor.apexx$getFogRenderer();
            Object guiRenderer = accessor.apexx$getGuiRenderer();
            if (fog != null && guiRenderer instanceof GuiRendererAccessor invoker) {
                invoker.apexx$invokeRender(fog.getFogBuffer(FogRenderer.FogType.NONE));
                contentDrawnThisFrame = true;
            }
        } catch (Throwable ignored) {
        } finally {
            hudFlushActive = false;
        }
    }

    // --------------------------------------------------------------------------------------------
    // Per-flush redirect (driven by GuiRendererMixin; may run more than once per real frame - once
    // from the manual early HUD flush above, once naturally at the real end of the frame)
    // --------------------------------------------------------------------------------------------

    /** HEAD of the GUI flush: decide whether to redirect this flush and prepare the offscreen buffer. */
    public static void beginFrame() {
        activeThisFrame = false;
        if (!shouldRedirect()) {
            return;
        }
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return;
            Framebuffer main = mc.getFramebuffer();
            if (main == null) return;
            int w = main.textureWidth;
            int h = main.textureHeight;
            if (w <= 0 || h <= 0) return;

            if (!ensureFbo(w, h)) return;
            if (!fboClearedThisFrame) {
                clearFbo();
                fboClearedThisFrame = true;
            }
            activeThisFrame = true;
            contentDrawnThisFrame = true;
        } catch (Throwable t) {
            activeThisFrame = false;
        }
    }

    /** Redirect target for MinecraftClient.getFramebuffer() inside GuiRenderer.renderPreparedDraws. */
    public static Framebuffer guiTarget(Framebuffer original) {
        if ((activeThisFrame || hudFlushActive) && fbo != null) {
            return fbo;
        }
        return original;
    }

    /** RETURN of the GUI flush: just clears this call's own redirect flag. Presentation is decided
     * once per real frame by {@link #finishFrame()}, since this flush can run more than once per
     * frame (the manual early HUD flush, then vanilla's own natural end-of-frame flush). */
    public static void endFrame() {
        activeThisFrame = false;
    }

    /**
     * RETURN of GameRenderer.render: the one, authoritative, once-per-real-frame decision of whether
     * to present the overlay window or hide it. Only presents once {@link SsProofPolicy.State#READY}
     * - while merely redirected (PENDING/BLOCKED) the content is still kept off the game framebuffer,
     * it is just not shown anywhere yet, so neither a capture nor the player's own screen ever gets a
     * half-verified frame.
     */
    public static void finishFrame() {
        try {
            if (contentDrawnThisFrame && fbo != null && currentState == SsProofPolicy.State.READY) {
                GpuTexture color = fbo.getColorAttachment();
                int glId = extractGlId(color);
                if (glId > 0) {
                    OverlayWindow.presentMirror(glId, fbW, fbH, false);
                    return;
                }
            }
            if (OverlayWindow.isShown()) {
                OverlayWindow.hide();
            }
        } catch (Throwable ignored) {
        }
    }

    // --------------------------------------------------------------------------------------------
    // Buffer lifecycle & helpers
    // --------------------------------------------------------------------------------------------

    private static boolean ensureFbo(int w, int h) {
        if (w <= 0 || h <= 0) return false;
        try {
            if (fbo == null) {
                fbo = new SimpleFramebuffer("apexx_overlay", w, h, true);
                fbW = w;
                fbH = h;
            } else if (w != fbW || h != fbH) {
                fbo.resize(w, h);
                fbW = w;
                fbH = h;
            }
        } catch (Throwable t) {
            try {
                if (fbo != null) fbo.delete();
            } catch (Throwable ignored) {
            }
            fbo = null;
            fbW = fbH = 0;
            return false;
        }
        return true;
    }

    private static void clearFbo() {
        try {
            var device = RenderSystem.tryGetDevice();
            if (device != null && fbo != null && fbo.getColorAttachment() != null) {
                var encoder = device.createCommandEncoder();
                if (fbo.getDepthAttachment() != null && fbo.useDepthAttachment) {
                    encoder.clearColorAndDepthTextures(fbo.getColorAttachment(), 0, fbo.getDepthAttachment(), 1.0);
                } else {
                    encoder.clearColorTexture(fbo.getColorAttachment(), 0);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static int extractGlId(GpuTexture tex) {
        if (tex == null) return -1;
        if (tex instanceof GlTexture glTex) {
            return glTex.getGlId();
        }
        for (Class<?> c = tex.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == int.class
                        && (m.getName().equals("getGlId") || m.getName().equals("glId"))) {
                    try {
                        m.setAccessible(true);
                        Object v = m.invoke(tex);
                        if (v instanceof Integer i && i > 0) return i;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        for (Class<?> c = tex.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == int.class && (f.getName().equals("glId") || f.getName().equals("id"))) {
                    try {
                        f.setAccessible(true);
                        int v = f.getInt(tex);
                        if (v > 0) return v;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return -1;
    }

    public static void dispose() {
        MinecraftClient mcClient = MinecraftClient.getInstance();
        if (mcClient != null && !mcClient.isOnThread()) {
            // GL objects are freed on the render thread only; a JVM shutdown hook leaves them to the OS.
            if (mcClient.isRunning()) mcClient.execute(OverlayCompositor::dispose);
            return;
        }
        try {
            if (fbo != null) {
                fbo.delete();
            }
        } catch (Throwable ignored) {
        }
        fbo = null;
        fbW = fbH = 0;
        activeThisFrame = false;
        worldVisualActiveThisFrame = false;
        fboClearedThisFrame = false;
        hudFlushActive = false;
        contentDrawnThisFrame = false;
        currentState = SsProofPolicy.State.OFF;
        pendingSinceMs = 0L;
        consecutiveAffinityFailures = 0;
        lastNoticeReason = SsProofPolicy.Reason.NONE;
    }
}
