package interstellarTikiTiks.apexx.client.shader;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.util.RenderBackendDetector;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.render.DefaultFramebufferSet;
import net.minecraft.client.util.Pool;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;

public class PostShaderManager {
    private static final Identifier OPENGL_BLUR_ID = Identifier.of(Apexx.MOD_ID, "glassmorphism");
    private static final Map<Identifier, Identifier> OPENGL_EFFECT_IDS = Map.of(
            Effects.CHROMATIC, Identifier.of(Apexx.MOD_ID, "customrenders/chromatic/chromatic"),
            Effects.WAVE, Identifier.of(Apexx.MOD_ID, "customrenders/wave/wave"),
            Effects.GLITCH, Identifier.of(Apexx.MOD_ID, "customrenders/glitch/glitch"),
            Effects.PIXELATE, Identifier.of(Apexx.MOD_ID, "customrenders/pixelate/pixelate"),
            Effects.RAINBOW, Identifier.of(Apexx.MOD_ID, "customrenders/rainbow/rainbow"));
    private static PostShaderManager INSTANCE;

    private final MinecraftClient client;
    private final Map<Identifier, PostEffectProcessor> loadedEffects = new ConcurrentHashMap<>();
    private final Map<Identifier, ShaderConfig> registeredEffects = new ConcurrentHashMap<>();
    private final Map<Identifier, Map<String, Float>> uniformsByEffect = new ConcurrentHashMap<>();
    private final Set<Identifier> dirtyEffects = ConcurrentHashMap.newKeySet();
    private final Stack<Identifier> effectStack = new Stack<>();

    private Identifier activeEffect = null;
    private boolean enabled;
    private Pool renderPool;

    private PostShaderManager() {
        this.client = MinecraftClient.getInstance();
        this.renderPool = new Pool(3);
        this.enabled = !RenderBackendDetector.isVulkanBackend();
        registerBuiltinShaders();
    }

    public static PostShaderManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PostShaderManager();
        }
        return INSTANCE;
    }

    public void registerBuiltinShaders() {
        registerShader(Effects.BLUR, ShaderConfig.builder()
                .uniform("Radius", 8.0f)
                .uniform("Brightness", 1.05f)
                .uniform("Alpha", 0.85f)
                .build());
        registerShader(Effects.CHROMATIC, ShaderConfig.builder()
                .uniform("Intensity", 5.0f)
                .uniform("Time", 0.0f)
                .build());
        registerShader(Effects.WAVE, ShaderConfig.builder()
                .uniform("Intensity", 5.0f)
                .uniform("Time", 0.0f)
                .build());
        registerShader(Effects.GLITCH, ShaderConfig.builder()
                .uniform("Intensity", 5.0f)
                .uniform("Time", 0.0f)
                .build());
        registerShader(Effects.PIXELATE, ShaderConfig.builder()
                .uniform("Intensity", 5.0f)
                .uniform("Time", 0.0f)
                .build());
        registerShader(Effects.RAINBOW, ShaderConfig.builder()
                .uniform("Time", 0.0f)
                .build());
    }

    public void registerShader(Identifier id, ShaderConfig config) {
        registeredEffects.put(id, config);
        uniformsByEffect.computeIfAbsent(id, key -> new ConcurrentHashMap<>()).putAll(config.getDefaultUniforms());
        dirtyEffects.add(id);
    }

    public void registerShader(Identifier id) {
        registerShader(id, ShaderConfig.DEFAULT);
    }

    public boolean supportsEffect(Identifier id) {
        if (id == null) {
            return false;
        }

        return !RenderBackendDetector.isVulkanBackend();
    }

    public PostEffectProcessor loadShader(Identifier id) {
        if (RenderBackendDetector.isVulkanBackend()) {
            return null;
        }

        Identifier resourceId = resolveOpenGlEffectId(id);
        try {
            PostEffectProcessor processor = client.getShaderLoader()
                    .loadPostEffect(resourceId, DefaultFramebufferSet.MAIN_ONLY);

            if (processor != null) {
                PostEffectProcessor previous = loadedEffects.put(id, processor);
                closeProcessor(previous);
                return processor;
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    public PostEffectProcessor getOrLoadShader(Identifier id) {
        if (RenderBackendDetector.isVulkanBackend()) {
            return null;
        }

        PostEffectProcessor processor = loadedEffects.get(id);
        if (processor == null) {
            processor = loadShader(id);
        }
        return processor;
    }

    public boolean applyEffect(Identifier id) {
        if (!enabled || id == null) {
            return false;
        }

        if (RenderBackendDetector.isVulkanBackend()) {
            clearEffect();
            return false;
        }

        activeEffect = id;
        dirtyEffects.add(id);
        return getOrLoadShader(id) != null;
    }

    public boolean pushEffect(Identifier id) {
        if (activeEffect != null) {
            effectStack.push(activeEffect);
        }
        return applyEffect(id);
    }

    public void popEffect() {
        if (!effectStack.isEmpty()) {
            applyEffect(effectStack.pop());
            return;
        }

        clearEffect();
    }

    public void clearEffect() {
        activeEffect = null;
        effectStack.clear();
    }

    public void render(Framebuffer framebuffer) {
        if (!enabled || activeEffect == null || framebuffer == null || RenderBackendDetector.isVulkanBackend()) {
            return;
        }

        PostEffectProcessor processor = loadedEffects.get(activeEffect);

        if (processor == null) {
            return;
        }

        try {
            processor.render(framebuffer, renderPool);
        } catch (Exception ignored) {
            clearEffect();
        }
    }

    public void renderOnce(Identifier id, Framebuffer framebuffer) {
        if (!enabled || id == null || framebuffer == null || RenderBackendDetector.isVulkanBackend()) {
            return;
        }

        PostEffectProcessor processor = getOrLoadShader(id);

        if (processor == null) {
            return;
        }

        try {
            processor.render(framebuffer, renderPool);
        } catch (Exception ignored) {
        }
    }

    public void setUniform(String uniformName, float value) {
        if (activeEffect == null) {
            return;
        }

        setUniform(activeEffect, uniformName, value);
    }

    public void setUniform(Identifier id, String uniformName, float value) {
        if (id == null || uniformName == null || uniformName.isBlank()) {
            return;
        }

        uniformsByEffect.computeIfAbsent(id, key -> new ConcurrentHashMap<>()).put(uniformName, value);
    }

    public boolean isEffectActive(Identifier id) {
        return enabled && Objects.equals(activeEffect, id);
    }

    public Identifier getActiveEffect() {
        return activeEffect;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clearEffect();
        }
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void clear() {
        for (PostEffectProcessor processor : loadedEffects.values()) {
            closeProcessor(processor);
        }
        loadedEffects.clear();
        activeEffect = null;
        effectStack.clear();
        dirtyEffects.clear();
        registerBuiltinShaders();
    }

    public void onResized(int width, int height) {
        if (activeEffect != null) {
            dirtyEffects.add(activeEffect);
            closeProcessor(loadedEffects.remove(activeEffect));
        }
    }

    public Set<Identifier> getRegisteredShaders() {
        return new HashSet<>(registeredEffects.keySet());
    }

    public ShaderConfig getConfig(Identifier id) {
        return registeredEffects.getOrDefault(id, ShaderConfig.DEFAULT);
    }

    private Identifier resolveOpenGlEffectId(Identifier id) {
        if (Effects.BLUR.equals(id)) {
            return OPENGL_BLUR_ID;
        }

        return OPENGL_EFFECT_IDS.getOrDefault(id, id);
    }

    private static void closeProcessor(PostEffectProcessor processor) {
        if (processor == null) {
            return;
        }

        try {
            processor.close();
        } catch (Exception ignored) {
        }
    }

    public static class ShaderConfig {
        public static final ShaderConfig DEFAULT = new ShaderConfig();

        private final boolean autoReload;
        private final boolean cacheable;
        private final Map<String, Float> defaultUniforms;

        public ShaderConfig() {
            this(true, true, new HashMap<>());
        }

        public ShaderConfig(boolean autoReload, boolean cacheable, Map<String, Float> defaultUniforms) {
            this.autoReload = autoReload;
            this.cacheable = cacheable;
            this.defaultUniforms = new HashMap<>(defaultUniforms);
        }

        public boolean shouldAutoReload() {
            return autoReload;
        }

        public boolean isCacheable() {
            return cacheable;
        }

        public Map<String, Float> getDefaultUniforms() {
            return new HashMap<>(defaultUniforms);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private boolean autoReload = true;
            private boolean cacheable = true;
            private final Map<String, Float> defaultUniforms = new HashMap<>();

            public Builder autoReload(boolean autoReload) {
                this.autoReload = autoReload;
                return this;
            }

            public Builder cacheable(boolean cacheable) {
                this.cacheable = cacheable;
                return this;
            }

            public Builder uniform(String name, float value) {
                this.defaultUniforms.put(name, value);
                return this;
            }

            public ShaderConfig build() {
                return new ShaderConfig(autoReload, cacheable, defaultUniforms);
            }
        }
    }

    public static class Effects {
        public static final Identifier BLUR = Identifier.ofVanilla("blur");
        public static final Identifier CREEPER = Identifier.ofVanilla("creeper");
        public static final Identifier SPIDER = Identifier.ofVanilla("spider");
        public static final Identifier INVERT = Identifier.ofVanilla("invert");
        public static final Identifier RAINBOW = Identifier.of(Apexx.MOD_ID, "rainbow");
        public static final Identifier CHROMATIC = Identifier.of(Apexx.MOD_ID, "chromatic");
        public static final Identifier WAVE = Identifier.of(Apexx.MOD_ID, "wave");
        public static final Identifier PIXELATE = Identifier.of(Apexx.MOD_ID, "pixelate");
        public static final Identifier GLITCH = Identifier.of(Apexx.MOD_ID, "glitch");

        public static void applyBlur(float radius) {
            PostShaderManager manager = getInstance();
            manager.applyEffect(BLUR);
            manager.setUniform("Radius", radius);
        }

        public static void applyTimed(Identifier effect, long durationMs) {
            PostShaderManager manager = getInstance();
            manager.applyEffect(effect);

            new Thread(() -> {
                try {
                    Thread.sleep(durationMs);
                    manager.clearEffect();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "apexx-post-effect-timer").start();
        }
    }
}
