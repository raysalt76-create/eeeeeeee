package interstellarTikiTiks.apexx.client.gui.sound;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;

import java.nio.ShortBuffer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Menu sounds (hover, click, switch on/off, open, close, slider tick, keybind set) in three packs: Creamy (soft,
 * deep thock), Clacky (bright, crisp switch) and Soft (gentle sine pops).
 *
 * <p>Every clip is synthesised as short mono PCM on a background thread (nothing is read from or written to disk)
 * and played through a small pool of OpenAL sources in Minecraft's own sound context. That context's listener
 * already carries the Master volume; the clip gain is the pack volume times the "User Interface" volume slider.
 * All playback happens on the client thread and every OpenAL call is guarded: if the sound system is missing or
 * a launcher's audio layer misbehaves (mobile), sounds simply stay silent.</p>
 */
public final class UiSounds {
    public enum Event { HOVER, CLICK, TOGGLE_ON, TOGGLE_OFF, OPEN, CLOSE, SLIDER, KEYBIND }

    private static final int RATE = 44100;
    private static final int POOL = 6;
    private static final long HOVER_GAP_NS = 55_000_000L;
    private static final long SLIDER_GAP_NS = 28_000_000L;

    // Synthesised clips, produced off-thread; read on the client thread.
    private static volatile Map<Event, short[]> clips;
    private static volatile String clipsPack;
    private static volatile boolean synthRunning;

    // OpenAL objects, client thread only.
    private static long alContext;
    private static String uploadedPack;
    private static final int[] buffers = new int[Event.values().length];
    private static final int[] sources = new int[POOL];
    private static int nextSource;
    private static int failures;
    private static long retryAfterNs;

    private static long lastHoverNs, lastSliderNs, lastOtherNs;

    // One click's bracket (client thread only).
    private static int pressDepth;
    private static Setting pressChanged;
    private static boolean[] pressModules;
    private static boolean pressPlayed;

    private UiSounds() {}

    // ------------------------------------------------------------------ settings

    private static boolean on(BooleanSetting setting) {
        return setting == null || setting.isEnabled();
    }

    private static boolean enabledFor(Event event) {
        if (ClickGUIModule.uiSounds == null || !ClickGUIModule.uiSounds.isEnabled()) return false;
        return switch (event) {
            case HOVER -> on(ClickGUIModule.soundHover);
            case CLICK -> on(ClickGUIModule.soundClick);
            case TOGGLE_ON, TOGGLE_OFF -> on(ClickGUIModule.soundToggle);
            case OPEN, CLOSE -> on(ClickGUIModule.soundOpenClose);
            case SLIDER -> on(ClickGUIModule.soundSlider);
            case KEYBIND -> on(ClickGUIModule.soundKeybind);
        };
    }

    private static String pack() {
        return ClickGUIModule.soundPack == null ? "Creamy" : ClickGUIModule.soundPack.getCurrentMode();
    }

    // ------------------------------------------------------------------ public API

    /** Starts synthesising the selected pack in the background, so the first sound is ready when it is needed. */
    public static void prewarm() {
        try {
            if (ClickGUIModule.uiSounds == null || !ClickGUIModule.uiSounds.isEnabled()) return;
            ensureClips(pack());
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    public static void play(Event event) {
        if (event == null) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return;
            if (!mc.isOnThread()) {
                mc.execute(() -> play(event));
                return;
            }
            if (!enabledFor(event)) return;
            long now = System.nanoTime();
            if (event == Event.HOVER) {
                // Hover is the quietest and most frequent: never stack it on another sound or on itself.
                if (now - lastHoverNs < HOVER_GAP_NS || now - lastOtherNs < HOVER_GAP_NS) return;
                lastHoverNs = now;
            } else if (event == Event.SLIDER) {
                if (now - lastSliderNs < SLIDER_GAP_NS) return;
                lastSliderNs = now;
            } else {
                lastOtherNs = now;
            }
            if (pressDepth > 0 && event != Event.HOVER) pressPlayed = true;
            emit(mc, event);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    /**
     * Bracket around one menu click. Whatever the click changed decides the sound: a module or switch turned on or
     * off plays the toggle sound, a slider jump plays a tick, anything else the plain click. Must be paired with
     * {@link #endPress(boolean)} on the client thread.
     */
    public static void beginPress() {
        try {
            if (pressDepth++ > 0) return;
            pressChanged = null;
            pressPlayed = false;
            pressModules = null;
            if (ClickGUIModule.uiSounds == null || !ClickGUIModule.uiSounds.isEnabled()) return;
            List<Module> modules = modules();
            if (modules != null) {
                boolean[] states = new boolean[modules.size()];
                for (int i = 0; i < states.length; i++) states[i] = modules.get(i).isEnabled();
                pressModules = states;
            }
            Setting.pressObserver = UiSounds::onSettingChanged;
        } catch (Throwable ignored) {
            Setting.pressObserver = null;
        }
    }

    /** @param handled whether the click hit anything; a click on empty space stays silent. */
    public static void endPress(boolean handled) {
        if (pressDepth <= 0) return;
        if (--pressDepth > 0) return;
        Setting.pressObserver = null;
        try {
            if (pressPlayed) return;
            Event event = null;
            List<Module> modules = modules();
            boolean[] before = pressModules;
            if (before != null && modules != null && modules.size() == before.length) {
                for (int i = 0; i < before.length; i++) {
                    boolean now = modules.get(i).isEnabled();
                    if (now != before[i]) {
                        event = now ? Event.TOGGLE_ON : Event.TOGGLE_OFF;
                        break;
                    }
                }
            }
            Setting changed = pressChanged;
            if (event == null && changed instanceof BooleanSetting bool) {
                event = bool.isEnabled() ? Event.TOGGLE_ON : Event.TOGGLE_OFF;
            } else if (event == null && changed instanceof SliderSetting) {
                event = Event.SLIDER;
            }
            if (event == null && (handled || changed != null)) event = Event.CLICK;
            if (event != null) play(event);
        } catch (Throwable ignored) {
            // cosmetic only
        } finally {
            pressChanged = null;
            pressModules = null;
            pressPlayed = false;
        }
    }

    private static void onSettingChanged(Setting setting) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || !mc.isOnThread() || pressDepth <= 0) return;
        if (pressChanged == null || (!(pressChanged instanceof BooleanSetting) && setting instanceof BooleanSetting)) {
            pressChanged = setting;
        }
    }

    private static List<Module> modules() {
        Apexx apexx = Apexx.getInstance();
        if (apexx == null || apexx.getModuleManager() == null) return null;
        return apexx.getModuleManager().getModules();
    }

    // ------------------------------------------------------------------ OpenAL playback

    private static void emit(MinecraftClient mc, Event event) {
        long now = System.nanoTime();
        if (failures >= 5 || now < retryAfterNs) return;
        String pack = pack();
        Map<Event, short[]> ready = clips;
        if (ready == null || !pack.equals(clipsPack)) {
            ensureClips(pack);
            return; // the sound for this event is skipped; the pack is ready a moment later
        }
        float uiVolume = 1f;
        if (mc.options != null) uiVolume = mc.options.getCategorySoundVolume(SoundCategory.UI);
        float volume = ClickGUIModule.soundVolume == null ? 0.6f : (float) ClickGUIModule.soundVolume.getValue() / 100f;
        float gain = Math.max(0f, Math.min(1f, volume * uiVolume));
        if (gain <= 0.001f) return;
        try {
            if (!ensureAl(pack, ready)) return;
            int buffer = buffers[event.ordinal()];
            if (buffer == 0) return;
            int source = pickSource();
            if (source == 0) return;
            float spread = ClickGUIModule.soundPitchVariation == null ? 0.08f
                    : (float) ClickGUIModule.soundPitchVariation.getValue() / 100f;
            float pitch = 1f + (spread > 0f ? (float) ThreadLocalRandom.current().nextDouble(-spread, spread) : 0f);
            AL10.alSourceStop(source);
            AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
            AL10.alSourcef(source, AL10.AL_GAIN, gain);
            AL10.alSourcef(source, AL10.AL_PITCH, Math.max(0.5f, Math.min(2f, pitch)));
            // No alGetError check here: the error flag is shared with Minecraft's sound thread, so a stray flag
            // may not be ours. A source or buffer that really went stale is caught by the context check above.
            AL10.alSourcePlay(source);
        } catch (Throwable failure) {
            noteFailure(now);
        }
    }

    private static void noteFailure(long now) {
        failures++;
        retryAfterNs = now + 5_000_000_000L;
        alContext = 0L; // rebuild everything on the next try
    }

    /** Sources and buffers live in Minecraft's current context; a device change or sound reload replaces it. */
    private static boolean ensureAl(String pack, Map<Event, short[]> ready) {
        long context = ALC10.alcGetCurrentContext();
        if (context == 0L) return false;
        if (context != alContext) {
            // Objects of a destroyed context are gone with it; just forget their names.
            java.util.Arrays.fill(buffers, 0);
            java.util.Arrays.fill(sources, 0);
            uploadedPack = null;
            for (int i = 0; i < POOL; i++) {
                int source = AL10.alGenSources();
                // alIsSource rather than alGetError: the error flag is shared with Minecraft's sound thread.
                if (source == 0 || !AL10.alIsSource(source)) break;
                sources[i] = source;
                AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
                AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);
                AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0f);
                AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
            }
            alContext = context;
        }
        if (!pack.equals(uploadedPack)) {
            for (int source : sources) {
                if (source == 0) continue;
                AL10.alSourceStop(source);
                AL10.alSourcei(source, AL10.AL_BUFFER, 0);
            }
            for (int i = 0; i < buffers.length; i++) {
                if (buffers[i] != 0) {
                    AL10.alDeleteBuffers(buffers[i]);
                    buffers[i] = 0;
                }
            }
            for (Event event : Event.values()) {
                short[] pcm = ready.get(event);
                if (pcm == null || pcm.length == 0) continue;
                int buffer = AL10.alGenBuffers();
                ShortBuffer data = BufferUtils.createShortBuffer(pcm.length);
                data.put(pcm).flip();
                if (buffer == 0 || !AL10.alIsBuffer(buffer)) continue;
                AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, data, RATE);
                buffers[event.ordinal()] = buffer;
            }
            uploadedPack = pack;
        }
        return true;
    }

    private static int pickSource() {
        for (int i = 0; i < POOL; i++) {
            int index = (nextSource + i) % POOL;
            int source = sources[index];
            if (source == 0) continue;
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                nextSource = (index + 1) % POOL;
                return source;
            }
        }
        // All busy: take the oldest in turn.
        int source = sources[nextSource];
        nextSource = (nextSource + 1) % POOL;
        return source;
    }

    // ------------------------------------------------------------------ synthesis

    private static void ensureClips(String pack) {
        if (pack.equals(clipsPack) && clips != null) return;
        if (synthRunning) return;
        synthRunning = true;
        Thread thread = new Thread(() -> {
            try {
                Map<Event, short[]> made = new EnumMap<>(Event.class);
                for (Event event : Event.values()) made.put(event, synth(pack, event));
                clips = made;
                clipsPack = pack;
            } catch (Throwable ignored) {
                // stays silent
            } finally {
                synthRunning = false;
            }
        }, "apexx-ui-sounds");
        thread.setDaemon(true);
        thread.start();
    }

    static short[] synth(String pack, Event event) {
        return switch (pack) {
            case "Clacky" -> clacky(event);
            case "Soft" -> soft(event);
            default -> creamy(event);
        };
    }

    /** Deep, muted "thock": a low body that drops in pitch under a dull, lowpassed tap. */
    private static short[] creamy(Event event) {
        Clip c = new Clip(event == Event.OPEN || event == Event.CLOSE ? 190 : 110);
        switch (event) {
            case HOVER -> { c.tone(0, 640, 520, 7, 0.5f); c.noise(0, 2600, 0, 3, 0.25f); return c.done(0.16f); }
            case CLICK -> { thock(c, 0, 250, 1f); return c.done(0.55f); }
            case TOGGLE_ON -> { thock(c, 0, 220, 0.9f); thock(c, 42, 330, 1f); return c.done(0.55f); }
            case TOGGLE_OFF -> { thock(c, 0, 330, 1f); thock(c, 42, 205, 0.9f); return c.done(0.52f); }
            case OPEN -> { c.sweep(0, 300, 620, 26, 130, 0.7f); thock(c, 0, 240, 0.6f); return c.done(0.42f); }
            case CLOSE -> { c.sweep(0, 560, 260, 18, 120, 0.7f); thock(c, 70, 210, 0.5f); return c.done(0.4f); }
            case SLIDER -> { c.tone(0, 980, 900, 4, 0.6f); c.noise(0, 3200, 0, 2, 0.3f); return c.done(0.13f); }
            case KEYBIND -> { thock(c, 0, 260, 0.9f); thock(c, 60, 390, 1f); return c.done(0.55f); }
        }
        return c.done(0.5f);
    }

    private static void thock(Clip c, float atMs, float hz, float amp) {
        c.tone(atMs, hz, hz * 0.6f, 24, amp);
        c.tone(atMs, hz * 2.02f, hz * 1.4f, 9, amp * 0.25f);
        c.noise(atMs, 1700, 0, 9, amp * 0.42f);
    }

    /** Bright, crisp switch: a highpassed snap on bottom-out, then a lighter one on release. */
    private static short[] clacky(Event event) {
        Clip c = new Clip(event == Event.OPEN || event == Event.CLOSE ? 160 : 90);
        switch (event) {
            case HOVER -> { c.noise(0, 9000, 3600, 2, 0.6f); c.tone(0, 3300, 3100, 2.5f, 0.3f); return c.done(0.14f); }
            case CLICK -> { clack(c, 0, 1f); clack(c, 30, 0.5f); return c.done(0.5f); }
            case TOGGLE_ON -> { clack(c, 0, 1f); c.tone(22, 1500, 1500, 18, 0.35f); c.tone(48, 2000, 2000, 20, 0.35f); return c.done(0.5f); }
            case TOGGLE_OFF -> { clack(c, 0, 1f); c.tone(22, 2000, 2000, 18, 0.35f); c.tone(48, 1500, 1500, 20, 0.35f); return c.done(0.48f); }
            case OPEN -> { for (int i = 0; i < 3; i++) { clack(c, i * 34, 0.6f + i * 0.2f); c.tone(i * 34, 1300 + i * 380, 1300 + i * 380, 14, 0.28f); } return c.done(0.42f); }
            case CLOSE -> { for (int i = 0; i < 3; i++) { clack(c, i * 34, 1f - i * 0.2f); c.tone(i * 34, 2060 - i * 380, 2060 - i * 380, 14, 0.28f); } return c.done(0.4f); }
            case SLIDER -> { c.noise(0, 10000, 4200, 1.4f, 0.7f); return c.done(0.12f); }
            case KEYBIND -> { clack(c, 0, 1f); clack(c, 55, 0.9f); c.tone(55, 2400, 2400, 16, 0.25f); return c.done(0.5f); }
        }
        return c.done(0.5f);
    }

    private static void clack(Clip c, float atMs, float amp) {
        c.noise(atMs, 8000, 2200, 4.5f, amp);
        c.tone(atMs, 2600, 2300, 5, amp * 0.35f);
        c.tone(atMs, 620, 480, 6, amp * 0.2f);
    }

    /** Gentle sine pops, no noise. */
    private static short[] soft(Event event) {
        Clip c = new Clip(event == Event.OPEN || event == Event.CLOSE ? 220 : 120);
        switch (event) {
            case HOVER -> { c.tone(0, 880, 860, 10, 0.5f, 1.5f); return c.done(0.1f); }
            case CLICK -> { c.tone(0, 720, 640, 18, 1f, 1.2f); return c.done(0.38f); }
            case TOGGLE_ON -> { c.tone(0, 600, 600, 22, 1f, 1.5f); c.tone(50, 900, 900, 30, 1f, 1.5f); return c.done(0.38f); }
            case TOGGLE_OFF -> { c.tone(0, 900, 900, 22, 1f, 1.5f); c.tone(50, 600, 600, 30, 1f, 1.5f); return c.done(0.36f); }
            case OPEN -> { c.sweep(0, 420, 840, 40, 150, 1f); return c.done(0.3f); }
            case CLOSE -> { c.sweep(0, 800, 400, 30, 140, 1f); return c.done(0.28f); }
            case SLIDER -> { c.tone(0, 1250, 1250, 5, 1f, 1f); return c.done(0.09f); }
            case KEYBIND -> { c.tone(0, 660, 660, 24, 1f, 1.5f); c.tone(60, 990, 990, 34, 1f, 1.5f); return c.done(0.38f); }
        }
        return c.done(0.35f);
    }

    /** A float mix buffer with a few additive layers, normalised to a peak at the end. */
    private static final class Clip {
        private final float[] mix;
        private long seed = 0x9E3779B97F4A7C15L;

        Clip(int lengthMs) {
            mix = new float[Math.max(1, lengthMs * RATE / 1000)];
        }

        void tone(float atMs, float f0, float f1, float decayMs, float amp) {
            tone(atMs, f0, f1, decayMs, amp, 0.4f);
        }

        /** Sine with an exponential glide from f0 to f1 and an exponential decay; a short attack avoids a pop. */
        void tone(float atMs, float f0, float f1, float decayMs, float amp, float attackMs) {
            int start = (int) (atMs * RATE / 1000f);
            float decay = Math.max(0.3f, decayMs) * RATE / 1000f;
            float attack = Math.max(1f, attackMs * RATE / 1000f);
            int length = (int) Math.min(mix.length - start, decay * 7f);
            double phase = 0;
            for (int i = 0; i < length; i++) {
                float glide = (float) Math.exp(-i / (decay * 1.5f));
                double hz = f1 + (f0 - f1) * glide;
                phase += 2 * Math.PI * hz / RATE;
                float env = (float) Math.exp(-i / decay) * Math.min(1f, i / attack);
                mix[start + i] += (float) Math.sin(phase) * env * amp;
            }
        }

        /** Rising or falling sine sweep with its own attack/decay, for open and close. */
        void sweep(float atMs, float f0, float f1, float attackMs, float lengthMs, float amp) {
            int start = (int) (atMs * RATE / 1000f);
            int length = (int) Math.min(mix.length - start, lengthMs * RATE / 1000f);
            float attack = Math.max(1f, attackMs * RATE / 1000f);
            double phase = 0;
            for (int i = 0; i < length; i++) {
                float t = i / (float) length;
                double hz = f0 * Math.pow(f1 / f0, t);
                phase += 2 * Math.PI * hz / RATE;
                float env = Math.min(1f, i / attack) * (1f - t) * (1f - t);
                mix[start + i] += (float) (Math.sin(phase) + 0.18 * Math.sin(phase * 2)) * env * amp;
            }
        }

        /** White noise through a one-pole lowpass (and an optional one-pole highpass), exponentially decayed. */
        void noise(float atMs, float lowpassHz, float highpassHz, float decayMs, float amp) {
            int start = (int) (atMs * RATE / 1000f);
            float decay = Math.max(0.3f, decayMs) * RATE / 1000f;
            int length = (int) Math.min(mix.length - start, decay * 7f);
            float lpA = 1f - (float) Math.exp(-2 * Math.PI * lowpassHz / RATE);
            float hpA = highpassHz > 0 ? 1f - (float) Math.exp(-2 * Math.PI * highpassHz / RATE) : 0f;
            float lp = 0f, hpLow = 0f;
            for (int i = 0; i < length; i++) {
                seed ^= seed << 13;
                seed ^= seed >>> 7;
                seed ^= seed << 17;
                float white = ((seed >>> 40) / (float) (1L << 24)) * 2f - 1f;
                lp += lpA * (white - lp);
                float sample = lp;
                if (hpA > 0f) {
                    hpLow += hpA * (sample - hpLow);
                    sample -= hpLow;
                }
                mix[start + i] += sample * (float) Math.exp(-i / decay) * amp;
            }
        }

        short[] done(float peak) {
            float max = 0f;
            for (float v : mix) max = Math.max(max, Math.abs(v));
            float scale = max > 0f ? peak / max : 0f;
            int fade = Math.min(mix.length, RATE / 500);
            short[] out = new short[mix.length];
            for (int i = 0; i < mix.length; i++) {
                float v = mix[i] * scale;
                int fromEnd = mix.length - 1 - i;
                if (fromEnd < fade) v *= fromEnd / (float) fade;
                out[i] = (short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
            }
            return out;
        }
    }
}
