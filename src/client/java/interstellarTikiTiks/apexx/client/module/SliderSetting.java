package interstellarTikiTiks.apexx.client.module;

import net.minecraft.util.math.MathHelper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public class SliderSetting extends Setting {
    // Existing settings use at most 10 places; bound external precision to protect formatting and rounding.
    private static final int MAX_DECIMAL_PLACES = 10;

    private double value;
    private final double min;
    private final double max;
    private final int decimalPlaces;

    /**
     * @param defaultValue starting value, clamped into [min, max]
     * @param min          lower bound
     * @param max          upper bound
     *
     * <p>The argument order is (default, min, max) and is easy to get wrong. Several
     * modules shipped with the bounds transposed, and because {@code MathHelper.clamp}
     * evaluates as {@code value < min ? min : min(value, max)} an inverted pair does
     * not throw -- it silently pins the setting to the intended <em>maximum</em> and
     * leaves the GUI slider undraggable. That produced a 250 ms tap duration, a 200 ms
     * pre-delay, a 1000 ms backtrack and a 16-block range that nobody chose.
     *
     * <p>Bounds are therefore normalised here rather than trusted.
     */
    public SliderSetting(String name, double defaultValue, double min, double max, int decimalPlaces) {
        super(name);
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        this.decimalPlaces = Math.max(0, Math.min(MAX_DECIMAL_PLACES, decimalPlaces));
        setValue(defaultValue);
    }

    public double getValue() {
        return round(this.value);
    }

    public double getPreciseValue() {
        return this.value;
    }

    public void setValue(double value) {
        // A NaN (e.g. from a hand-edited or corrupted config) passes clamp unchanged and made
        // getValue() throw on every call, parking the module. Keep the current value instead.
        if (!Double.isFinite(value)) return;
        double clamped = MathHelper.clamp(value, this.min, this.max);
        if (Double.compare(this.value, clamped) != 0) {
            this.value = clamped;
            callListener();
            return;
        }
        this.value = clamped;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public int getDecimalPlaces() {
        return decimalPlaces;
    }

    private SliderSetting jitter;

    /**
     * Gives this (delay) slider a Gaussian standard-deviation companion. Its label puts the unit
     * last: "Extra Delay (ms)" gets "Extra Delay StDev (ms)"; a slider without a unit gets "&lt;name&gt; StDev".
     * {@link Module#addSetting} registers the companion right after this slider; it is only shown
     * while this slider is above its minimum, so a 0 delay stays an exact 0.
     */
    public SliderSetting withJitter(double defaultStdev) {
        if (jitter == null) {
            jitter = new SliderSetting(stdevLabel(getName()), defaultStdev, 0.0, Math.max(1.0, max * 0.5),
                    decimalPlaces);
            jitter.setDescription("How much " + getName()
                    + " varies each time. Most picks land close to the set value. 0 = always the exact value.");
            jitter.setDependsOn(getName());
        }
        return this;
    }

    /** "Extra Delay (ms)" -> "Extra Delay StDev (ms)"; "Hold" -> "Hold StDev". */
    static String stdevLabel(String name) {
        if (name != null && name.endsWith(")")) {
            int open = name.lastIndexOf(" (");
            if (open > 0) return name.substring(0, open) + " StDev" + name.substring(open);
        }
        return name + " StDev";
    }

    public SliderSetting getJitter() {
        return jitter;
    }

    /**
     * The value to act on: exactly {@link #getValue()} without a StDev companion (or a 0 StDev),
     * otherwise a Gaussian draw around it, truncated to +-2.5 sigma and to this slider's bounds.
     */
    public double sample() {
        double mean = getValue();
        double sigma = jitter == null ? 0.0 : jitter.getValue();
        if (sigma <= 0.0 || mean <= min) return mean;
        double g = java.util.concurrent.ThreadLocalRandom.current().nextGaussian();
        g = Math.max(-2.5, Math.min(2.5, g));
        return MathHelper.clamp(mean + g * sigma, Math.max(0.0, min), max);
    }

    public String getValueAsString() {
        return String.format(Locale.ROOT, "%." + decimalPlaces + "f", getValue());
    }

    /**
     * Maps this slider's position linearly onto [lo, hi]. Lets a module show a common 1-100
     * scale while feeding its own internal speed range to the code that uses it.
     */
    public double scaled(double lo, double hi) {
        double span = max - min;
        double t = span <= 0 ? 0 : (getValue() - min) / span;
        return lo + t * (hi - lo);
    }

    private double defaultValue = Double.NaN;

    @Override
    public void captureDefault() {
        defaultValue = value;
    }

    @Override
    public void resetToDefault() {
        if (!Double.isNaN(defaultValue)) setValue(defaultValue);
    }

    /** The value recorded by {@link #captureDefault()}, or the current value when none was recorded. */
    public double getDefaultValue() {
        return Double.isNaN(defaultValue) ? getPreciseValue() : defaultValue;
    }

    @Override
    public boolean isModified() {
        return !Double.isNaN(defaultValue) && round(defaultValue) != getValue();
    }

    private double round(double value) {
        if (decimalPlaces <= 0) {
            return (double) Math.round(value);
        }
        return new BigDecimal(value)
                .setScale(decimalPlaces, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
