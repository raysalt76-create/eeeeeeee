package interstellarTikiTiks.apexx.client.gui.overlay;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.macosx.ObjCRuntime;
import org.lwjgl.system.windows.User32;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Locale;

/**
 * Secondary transparent GLFW overlay window for screenshare-proof ClickGUI rendering.
 *
 * <p>Runs in-process, sharing Minecraft's own OpenGL context, so textures can be blitted across
 * contexts without a CPU readback or any cross-process transport. On Windows the overlay window
 * handle is marked with OS-level capture exclusion ({@code WDA_EXCLUDEFROMCAPTURE}), and that
 * exclusion is read back with {@code GetWindowDisplayAffinity} rather than assumed from the set
 * call's return value, since the first call can land before the window has a composited surface.
 * On macOS the window is marked {@code NSWindowSharingNone}.
 *
 * <p>Mouse clicks pass directly through to Minecraft via {@code GLFW_MOUSE_PASSTHROUGH} and
 * {@code WS_EX_TRANSPARENT}, allowing the native {@link interstellarTikiTiks.apexx.client.gui.gpuui.GpuClickGuiScreen}
 * to handle all user interaction directly while keeping Minecraft completely visible and streaming on OBS.
 */
public final class OverlayWindow {
    private OverlayWindow() {}

    private static long handle = 0L;
    private static GLCapabilities overlayCaps;
    private static GLCapabilities mcCaps;
    private static boolean initFailed = false;
    private static long initFailedAtMs;
    private static int initAttempts;
    private static final int MAX_INIT_ATTEMPTS = 4;
    private static long lastAffinityCheckMs;
    private static int affinityRetries;
    private static final int MAX_AFFINITY_RETRIES = 12;
    private static boolean visible = false;

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    public static final boolean IS_WINDOWS = OS_NAME.contains("win");
    public static final boolean IS_MAC = OS_NAME.contains("mac") || OS_NAME.contains("darwin");

    private static boolean blitReady = false;
    private static boolean blitFailed = false;
    private static int blitProgram = 0;
    private static int blitVao = 0;
    private static int blitVbo = 0;
    private static int blitTexUniform = -1;
    private static int blitFlipUniform = -1;

    // Win32 Extended Window Styles
    private static final long WS_EX_NOACTIVATE = 0x08000000L;
    private static final long WS_EX_TOOLWINDOW = 0x00000080L;
    private static final long WS_EX_TRANSPARENT = 0x00000020L;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;
    private static final long HWND_TOPMOST = -1L;

    // Windows Display Affinity
    private static final int WDA_EXCLUDEFROMCAPTURE = 0x00000011;

    // Cached user32 function pointers so capture exclusion can be re-asserted and re-verified cheaply.
    private static SharedLibrary cachedUser32;
    private static long cachedSetAffinityFn;
    private static long cachedGetAffinityFn;
    private static boolean affinityLookupDone;

    /** See {@link SsProofPolicy#AFFINITY_UNKNOWN}/{@code AFFINITY_VERIFIED}/{@code AFFINITY_FAILED}. */
    private static int affinityState = SsProofPolicy.AFFINITY_UNKNOWN;

    // macOS NSWindow constants
    private static final long NS_WINDOW_SHARING_NONE = 0L;
    private static final long NS_FLOATING_WINDOW_LEVEL = 3L;
    private static final long NS_COLLECTION_CAN_JOIN_ALL_SPACES = 1L << 0;
    private static final long NS_COLLECTION_STATIONARY = 1L << 4;
    private static final long NS_COLLECTION_FULLSCREEN_AUXILIARY = 1L << 8;
    private static final long NS_COLLECTION_OVERLAY = NS_COLLECTION_CAN_JOIN_ALL_SPACES
            | NS_COLLECTION_STATIONARY | NS_COLLECTION_FULLSCREEN_AUXILIARY;

    private static long mcHandle() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return (mc != null && mc.getWindow() != null) ? mc.getWindow().getHandle() : 0L;
    }

    public static boolean isShown() {
        return handle != 0L && visible;
    }

    /** True once the overlay window itself has been created (regardless of capture-exclusion state). */
    public static boolean isAvailable() {
        return handle != 0L && !initFailed;
    }

    /** See {@link SsProofPolicy#AFFINITY_UNKNOWN}/{@code AFFINITY_VERIFIED}/{@code AFFINITY_FAILED}. */
    public static int affinityState() {
        return affinityState;
    }

    /** True once window creation failed repeatedly: the policy then resolves to BLOCKED instead of waiting forever. */
    public static boolean creationGaveUp() {
        return initFailed && initAttempts >= MAX_INIT_ATTEMPTS;
    }

    public static void ensureCreated() {
        if (handle != 0L) {
            recheckAffinity();
            return;
        }
        if (initFailed) {
            // A failed creation is retried a few times (a transient driver/window error) before it is final.
            if (initAttempts >= MAX_INIT_ATTEMPTS || System.currentTimeMillis() - initFailedAtMs < 3000L) return;
            initFailed = false;
        }
        long prev = GLFW.glfwGetCurrentContext();
        try {
            long mc = mcHandle();
            if (mc == 0L) return;
            mcCaps = GL.getCapabilities();

            int[] w = new int[1], h = new int[1];
            GLFW.glfwGetWindowSize(mc, w, h);

            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FLOATING, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
            try {
                GLFW.glfwWindowHint(GLFW.GLFW_MOUSE_PASSTHROUGH, GLFW.GLFW_TRUE);
            } catch (Throwable ignored) {
            }

            if (IS_MAC) {
                GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            }

            handle = GLFW.glfwCreateWindow(Math.max(1, w[0]), Math.max(1, h[0]), SsProofPolicy.randomWindowTitle(new java.util.Random()), 0L, mc);
            if (handle == 0L) {
                markInitFailed();
                return;
            }

            GLFW.glfwMakeContextCurrent(handle);
            overlayCaps = GL.createCapabilities();
            GLFW.glfwSwapInterval(0);
            syncBounds();
            applyMousePassthrough();
            applyCaptureExclusion();
            applyMacOSSharingExclusion();
        } catch (Throwable t) {
            markInitFailed();
        } finally {
            restore(prev);
        }
    }

    private static void markInitFailed() {
        initFailed = true;
        initFailedAtMs = System.currentTimeMillis();
        initAttempts++;
        if (handle != 0L) {
            try { GLFW.glfwDestroyWindow(handle); } catch (Throwable ignored) { }
            handle = 0L;
        }
    }

    /** Re-reads (and re-applies) the capture exclusion until it verifies, at most once a second, with a cap. */
    private static void recheckAffinity() {
        if (!IS_WINDOWS || affinityState == SsProofPolicy.AFFINITY_VERIFIED) return;
        long now = System.currentTimeMillis();
        if (now - lastAffinityCheckMs < 1000L) return;
        lastAffinityCheckMs = now;
        applyCaptureExclusion();
        if (affinityState != SsProofPolicy.AFFINITY_VERIFIED && ++affinityRetries >= MAX_AFFINITY_RETRIES
                && affinityState == SsProofPolicy.AFFINITY_UNKNOWN) {
            // Never verifiable: report it as failed so the policy resolves to BLOCKED with an accurate notice
            // (and the opt-in fail-open can act) instead of staying "starting" forever.
            affinityState = SsProofPolicy.AFFINITY_FAILED;
        }
    }

    public static void show() {
        if (handle == 0L) {
            ensureCreated();
        }
        if (handle == 0L) return;
        syncBounds();
        if (!visible) {
            GLFW.glfwShowWindow(handle);
            applyMousePassthrough();
            applyCaptureExclusion();
            applyMacOSSharingExclusion();
            if (IS_WINDOWS) {
                try {
                    long hwnd = GLFWNativeWin32.glfwGetWin32Window(handle);
                    if (hwnd != 0L) {
                        User32.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0,
                                SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
                    }
                } catch (Throwable ignored) {
                }
            }
            visible = true;
        }
    }

    private static boolean gameWindowActive() {
        try {
            long mc = mcHandle();
            if (mc == 0L) return false;
            return GLFW.glfwGetWindowAttrib(mc, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE
                    && GLFW.glfwGetWindowAttrib(mc, GLFW.GLFW_ICONIFIED) != GLFW.GLFW_TRUE;
        } catch (Throwable t) {
            return true;
        }
    }

    public static void hide() {
        if (handle != 0L && visible) {
            GLFW.glfwHideWindow(handle);
        }
        visible = false;
    }

    public static void syncBounds() {
        if (handle == 0L) return;
        try {
            long mc = mcHandle();
            if (mc == 0L) return;
            int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
            GLFW.glfwGetWindowPos(mc, x, y);
            GLFW.glfwGetWindowSize(mc, w, h);
            GLFW.glfwSetWindowPos(handle, x[0], y[0]);
            GLFW.glfwSetWindowSize(handle, Math.max(1, w[0]), Math.max(1, h[0]));
        } catch (Throwable ignored) {
        }
    }

    public static void applyMousePassthrough() {
        if (handle == 0L) return;
        try {
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_MOUSE_PASSTHROUGH, GLFW.GLFW_TRUE);
        } catch (Throwable ignored) {
        }
        if (IS_WINDOWS) {
            try {
                long hwnd = GLFWNativeWin32.glfwGetWin32Window(handle);
                if (hwnd != 0L) {
                    long ex = User32.GetWindowLongPtr(hwnd, User32.GWL_EXSTYLE);
                    long updated = (ex & ~WS_EX_TRANSPARENT) | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW;
                    if (updated != ex) {
                        User32.SetWindowLongPtr(hwnd, User32.GWL_EXSTYLE, updated);
                        User32.SetWindowPos(hwnd, 0L, 0, 0, 0, 0,
                                SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void applyCaptureExclusion() {
        if (!IS_WINDOWS || handle == 0L) {
            return;
        }
        try {
            long hwnd = GLFWNativeWin32.glfwGetWin32Window(handle);
            if (hwnd == 0L) return;
            if (!affinityLookupDone) {
                affinityLookupDone = true;
                cachedUser32 = APIUtil.apiCreateLibrary("user32");
                cachedSetAffinityFn = APIUtil.apiGetFunctionAddressOptional(cachedUser32, "SetWindowDisplayAffinity");
                cachedGetAffinityFn = APIUtil.apiGetFunctionAddressOptional(cachedUser32, "GetWindowDisplayAffinity");
            }
            if (cachedSetAffinityFn != 0L) {
                int setResult = JNI.invokePI(hwnd, WDA_EXCLUDEFROMCAPTURE, cachedSetAffinityFn);
            }
            // Read the real affinity back instead of trusting the set call's return value: the first
            // call can land before the window has a composited surface, which previously left the
            // overlay fully capturable despite the toggle showing on.
            affinityState = verifyCaptureExclusion(hwnd);
        } catch (Throwable t) {
            affinityState = SsProofPolicy.AFFINITY_FAILED;
        }
    }

    private static int verifyCaptureExclusion(long hwnd) {
        if (cachedGetAffinityFn == 0L) return SsProofPolicy.AFFINITY_UNKNOWN;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer out = stack.mallocInt(1);
            out.put(0, 0);
            int ok = JNI.invokePPI(hwnd, MemoryUtil.memAddress(out), cachedGetAffinityFn);
            int readBack = out.get(0);
            if (ok == 0) return SsProofPolicy.AFFINITY_UNKNOWN;
            return readBack == WDA_EXCLUDEFROMCAPTURE ? SsProofPolicy.AFFINITY_VERIFIED : SsProofPolicy.AFFINITY_FAILED;
        } catch (Throwable t) {
            return SsProofPolicy.AFFINITY_UNKNOWN;
        }
    }

    private static void applyMacOSSharingExclusion() {
        if (!IS_MAC || handle == 0L) return;
        try {
            long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(handle);
            if (nsWindow == 0L) return;
            long msgSendFn = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
            if (msgSendFn == 0L) return;

            objcSend1(nsWindow, msgSendFn, "setSharingType:", NS_WINDOW_SHARING_NONE);
            objcSend1(nsWindow, msgSendFn, "setCollectionBehavior:", NS_COLLECTION_OVERLAY);
            objcSend1(nsWindow, msgSendFn, "setLevel:", NS_FLOATING_WINDOW_LEVEL);
        } catch (Throwable ignored) {
        }
    }

    private static void objcSend1(long obj, long msgSendFn, String selectorName, long arg) {
        long sel = ObjCRuntime.sel_getUid(selectorName);
        if (sel != 0L) {
            JNI.invokePPPV(obj, sel, arg, msgSendFn);
        }
    }

    public static void presentMirror(int glTexId, int texW, int texH, boolean flipV) {
        if (glTexId <= 0) return;
        // The overlay is always-on-top: when Minecraft loses focus (alt-tab) or is minimised it
        // kept the menu drawn over whatever app was in front. Hide it until the game is back.
        if (!gameWindowActive()) {
            hide();
            return;
        }
        if (!visible) {
            show();
        }
        if (handle == 0L || !visible) return;

        long prev = GLFW.glfwGetCurrentContext();
        try {
            // The texture is rendered in Minecraft's context and sampled in ours. glFinish stalled
            // the CPU on the whole GPU queue every frame; a fence makes only the overlay's GPU
            // commands wait for the texture, without blocking this thread.
            long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL11.glFlush();
            GLFW.glfwMakeContextCurrent(handle);
            GL.setCapabilities(overlayCaps);
            if (fence != 0L) {
                GL32.glWaitSync(fence, 0, GL32.GL_TIMEOUT_IGNORED);
                GL32.glDeleteSync(fence);
            }

            syncBounds();

            int[] fw = new int[1], fh = new int[1];
            GLFW.glfwGetFramebufferSize(handle, fw, fh);
            GL11.glViewport(0, 0, fw[0], fh[0]);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            if (!ensureBlit()) {
                GLFW.glfwSwapBuffers(handle);
                return;
            }

            GL20.glUseProgram(blitProgram);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexId);
            if (blitTexUniform >= 0) GL20.glUniform1i(blitTexUniform, 0);
            if (blitFlipUniform >= 0) GL20.glUniform1f(blitFlipUniform, flipV ? 1f : 0f);
            GL30.glBindVertexArray(blitVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            GL30.glBindVertexArray(0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL20.glUseProgram(0);

            GLFW.glfwSwapBuffers(handle);
        } catch (Throwable ignored) {
        } finally {
            restore(prev);
        }
    }

    private static boolean ensureBlit() {
        if (blitReady) return true;
        if (blitFailed) return false;
        try {
            String vs = "#version 150 core\n"
                    + "in vec2 aPos;\nin vec2 aUv;\nout vec2 vUv;\n"
                    + "void main(){ vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }\n";
            String fs = "#version 150 core\n"
                    + "in vec2 vUv;\nout vec4 frag;\nuniform sampler2D uTex;\nuniform float uFlipV;\n"
                    + "void main(){ vec2 uv = vec2(vUv.x, mix(vUv.y, 1.0 - vUv.y, uFlipV));"
                    + " frag = texture(uTex, uv); }\n";

            int vsId = compile(GL20.GL_VERTEX_SHADER, vs);
            int fsId = compile(GL20.GL_FRAGMENT_SHADER, fs);
            blitProgram = GL20.glCreateProgram();
            GL20.glAttachShader(blitProgram, vsId);
            GL20.glAttachShader(blitProgram, fsId);
            GL20.glBindAttribLocation(blitProgram, 0, "aPos");
            GL20.glBindAttribLocation(blitProgram, 1, "aUv");
            GL20.glLinkProgram(blitProgram);
            if (GL20.glGetProgrami(blitProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteShader(vsId);
                GL20.glDeleteShader(fsId);
                GL20.glDeleteProgram(blitProgram);
                blitProgram = 0;
                blitFailed = true;
                return false;
            }
            GL20.glDeleteShader(vsId);
            GL20.glDeleteShader(fsId);
            blitTexUniform = GL20.glGetUniformLocation(blitProgram, "uTex");
            blitFlipUniform = GL20.glGetUniformLocation(blitProgram, "uFlipV");

            float[] verts = {
                    -1f, -1f, 0f, 0f,
                     1f, -1f, 1f, 0f,
                    -1f,  1f, 0f, 1f,
                     1f,  1f, 1f, 1f,
            };
            FloatBuffer buf = BufferUtils.createFloatBuffer(verts.length);
            buf.put(verts).flip();

            blitVao = GL30.glGenVertexArrays();
            blitVbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(blitVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, blitVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0L);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8L);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            blitReady = true;
            return true;
        } catch (Throwable t) {
            blitFailed = true;
            return false;
        }
    }

    private static int compile(int type, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        return id;
    }

    private static void restore(long prev) {
        try {
            GLFW.glfwMakeContextCurrent(prev);
            if (GLFW.glfwGetCurrentContext() != prev) {
                GLFW.glfwMakeContextCurrent(prev);
                if (GLFW.glfwGetCurrentContext() != prev && prev == 0L) {
                    long mc = mcHandle();
                    if (mc != 0L) {
                        GLFW.glfwMakeContextCurrent(mc);
                    }
                }
            }
            if (mcCaps != null) {
                GL.setCapabilities(mcCaps);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void handOffFocusToGame() {
        try {
            long mc = mcHandle();
            if (mc != 0L && handle != 0L
                    && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE) {
                GLFW.glfwFocusWindow(mc);
            }
        } catch (Throwable ignored) {
        }
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) return;
            client.execute(() -> {
                try {
                    if (client.currentScreen == null && client.mouse != null
                            && !client.mouse.isCursorLocked()) {
                        client.mouse.lockCursor();
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static void destroy() {
        MinecraftClient mcClient = MinecraftClient.getInstance();
        if (mcClient != null && !mcClient.isOnThread()) {
            // GLFW and GL objects belong to the render thread. While the game still runs, hand the teardown
            // back to it; from a JVM shutdown hook the OS reclaims the window, so nothing is touched here.
            if (mcClient.isRunning()) mcClient.execute(OverlayWindow::destroy);
            return;
        }
        if (handle != 0L) {
            long prev = GLFW.glfwGetCurrentContext();
            try {
                if (blitProgram != 0 || blitVao != 0 || blitVbo != 0) {
                    GLFW.glfwMakeContextCurrent(handle);
                    if (overlayCaps != null) GL.setCapabilities(overlayCaps);
                    if (blitProgram != 0) GL20.glDeleteProgram(blitProgram);
                    if (blitVao != 0) GL30.glDeleteVertexArrays(blitVao);
                    if (blitVbo != 0) GL15.glDeleteBuffers(blitVbo);
                }
            } catch (Throwable ignored) {
            } finally {
                restore(prev);
            }
            GLFW.glfwDestroyWindow(handle);
            handle = 0L;
        }
        visible = false;
        initFailed = false;
        affinityState = SsProofPolicy.AFFINITY_UNKNOWN;
        affinityLookupDone = false;
        blitReady = false;
        blitFailed = false;
        initAttempts = 0;
        affinityRetries = 0;
        blitProgram = 0;
        blitVao = 0;
        blitVbo = 0;
        overlayCaps = null;
        OverlayCompositor.dispose();
    }
}
