package interstellarTikiTiks.apexx.client.util.render;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every Apexx render pipeline instead of vanilla's {@code RenderPipelines.register}.
 *
 * <p>Vanilla precompiles every registered pipeline in {@code ShaderLoader.apply} and throws
 * "Failed to load required shader programs" if any of them fails, which aborts startup or the resource
 * reload on drivers that reject one of our shaders (Apple GL, Mesa, GLES translation layers). Pipelines
 * registered here are never on that fatal list. Instead {@link #precompileAll()} runs right after vanilla's
 * shader load and after every resource reload (ShaderLoaderPrecompileMixin), compiling each pipeline one at
 * a time on the render thread with its own try/catch. Each pipeline gets a status: OK, or FAILED with the
 * driver's compile/link log, which is logged once. A failed pipeline is not retried until the next reload,
 * and its feature checks {@link #usable(RenderPipeline)} to disable itself or fall back.
 */
public final class SafePipelines {
    private static final Logger LOGGER = LoggerFactory.getLogger("ApexxShaders");

    public enum Status { PENDING, OK, FAILED }

    private record State(Status status, String error) { }

    private static final State PENDING = new State(Status.PENDING, null);
    private static final State OK = new State(Status.OK, null);
    private static final Map<RenderPipeline, State> STATES = new ConcurrentHashMap<>();
    /** Set once vanilla's ShaderLoader has applied shader sources at least once. */
    private static volatile boolean shadersLoaded;

    private SafePipelines() { }

    /**
     * Tracks a pipeline (replacement for {@code RenderPipelines.register}). Compiles it immediately when
     * shader sources are already loaded and this is the render thread; otherwise at the next precompile.
     */
    public static RenderPipeline register(RenderPipeline pipeline) {
        if (pipeline == null) return null;
        STATES.putIfAbsent(pipeline, PENDING);
        if (shadersLoaded && onRenderThread()) compile(pipeline);
        return pipeline;
    }

    /**
     * True when the pipeline compiled and linked on this device. Never compiles more than once per
     * resource reload: a FAILED pipeline stays failed until the next reload.
     */
    public static boolean usable(RenderPipeline pipeline) {
        if (pipeline == null) return false;
        State state = STATES.get(pipeline);
        if (state == null) {
            STATES.putIfAbsent(pipeline, PENDING);
            state = PENDING;
        }
        if (state.status == Status.PENDING) {
            if (!onRenderThread() || !canCompileLazily()) return false;
            return compile(pipeline) == Status.OK;
        }
        return state.status == Status.OK;
    }

    public static Status status(RenderPipeline pipeline) {
        State state = pipeline == null ? null : STATES.get(pipeline);
        return state == null ? Status.PENDING : state.status;
    }

    /** Driver compile/link log of a FAILED pipeline, or null. */
    public static String error(RenderPipeline pipeline) {
        State state = pipeline == null ? null : STATES.get(pipeline);
        return state == null ? null : state.error;
    }

    /**
     * Called at the end of ShaderLoader.apply (startup and every resource reload). Resets every status and
     * compiles each tracked pipeline in turn; one failure never stops the others or the reload.
     */
    public static void precompileAll() {
        shadersLoaded = true;
        if (!onRenderThread()) {
            RenderSystem.queueFencedTask(SafePipelines::precompileAll);
            return;
        }
        List<RenderPipeline> pipelines = new ArrayList<>(STATES.keySet());
        for (RenderPipeline pipeline : pipelines) STATES.put(pipeline, PENDING);
        int failed = 0;
        for (RenderPipeline pipeline : pipelines) {
            if (compile(pipeline) == Status.FAILED) failed++;
        }
        if (failed > 0) {
            LOGGER.warn("Apexx: {} of {} render pipelines failed to compile on this device; those effects are off until the next resource reload",
                    failed, pipelines.size());
        }
    }

    private static Status compile(RenderPipeline pipeline) {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) return Status.PENDING;
        String location = String.valueOf(pipeline.getLocation());
        boolean valid = false;
        String error = null;
        DriverLogCapture capture = DriverLogCapture.start();
        try {
            CompiledRenderPipeline compiled = device.precompilePipeline(pipeline);
            valid = compiled != null && compiled.isValid();
        } catch (RuntimeException | LinkageError failure) {
            error = failure.toString();
        } finally {
            String log = capture.stop();
            if (!valid && error == null) error = log;
            else if (!valid && log != null && !log.isEmpty()) error = error + "\n" + log;
        }
        if (valid) {
            STATES.put(pipeline, OK);
            return Status.OK;
        }
        if (error == null || error.isBlank()) error = "invalid program (no driver log captured; see the GlBackend lines above)";
        STATES.put(pipeline, new State(Status.FAILED, error));
        LOGGER.warn("Apexx render pipeline {} failed to compile/link; its effect is disabled until the next resource reload. Driver log: {}",
                location, error);
        try {
            ClientInternalLog.warn("Shaders", "Pipeline " + location + " failed: " + firstLine(error));
        } catch (RuntimeException | LinkageError ignored) { }
        return Status.FAILED;
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    private static boolean onRenderThread() {
        try {
            return RenderSystem.isOnRenderThread();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Lazy compile path for pipelines first used after the precompile (material pipelines are created on
     * demand) or when the precompile hook did not apply. Never before the first shader load: the sources
     * would be missing and the pipeline would be wrongly marked FAILED.
     */
    private static boolean canCompileLazily() {
        if (shadersLoaded) return true;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getOverlay() != null) return false;
        // Initial load finished but the hook never ran (mixin not applied): adopt the loaded sources.
        shadersLoaded = true;
        return true;
    }

    /**
     * Captures WARN/ERROR lines logged on this thread while one pipeline compiles, so the driver's
     * compile/link info log that GlBackend prints can be stored with the FAILED status. Optional: when
     * log4j-core is unavailable it silently captures nothing.
     */
    private static final class DriverLogCapture {
        private static final DriverLogCapture NONE = new DriverLogCapture(null);
        private final Object handle;

        private DriverLogCapture(Object handle) { this.handle = handle; }

        static DriverLogCapture start() {
            try {
                return new DriverLogCapture(Log4jCapture.begin());
            } catch (RuntimeException | LinkageError ignored) {
                return NONE;
            }
        }

        String stop() {
            if (handle == null) return null;
            try {
                return Log4jCapture.end(handle);
            } catch (RuntimeException | LinkageError ignored) {
                return null;
            }
        }
    }

    /** Isolated so a missing log4j-core only fails this class, not SafePipelines. */
    private static final class Log4jCapture extends org.apache.logging.log4j.core.appender.AbstractAppender {
        private static final String NAME = "ApexxPipelineCapture";
        private final long threadId = Thread.currentThread().getId();
        private final StringBuilder lines = new StringBuilder();

        private Log4jCapture() {
            super(NAME, null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        static Object begin() {
            org.apache.logging.log4j.core.LoggerContext context =
                    (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
            Log4jCapture appender = new Log4jCapture();
            appender.start();
            context.getConfiguration().getRootLogger()
                    .addAppender(appender, org.apache.logging.log4j.Level.WARN, null);
            return appender;
        }

        static String end(Object handle) {
            Log4jCapture appender = (Log4jCapture) handle;
            org.apache.logging.log4j.core.LoggerContext context =
                    (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
            context.getConfiguration().getRootLogger().removeAppender(NAME);
            appender.stop();
            synchronized (appender.lines) {
                return appender.lines.toString().trim();
            }
        }

        @Override
        public void append(org.apache.logging.log4j.core.LogEvent event) {
            if (event == null || event.getThreadId() != threadId) return;
            if (!event.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.WARN)) return;
            String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            if (message.startsWith("Apexx render pipeline")) return;
            synchronized (lines) {
                if (lines.length() < 8192) lines.append(message).append('\n');
            }
        }
    }
}
