package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.render.ScreenProjector;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import org.joml.Matrix3x2fStack;

import java.awt.Color;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Floating damage numbers.
 *
 * <p>Amounts come from the client's view of each living entity's health + absorption, sampled once per
 * tick: a drop is damage, a rise (optionally) a heal. The server's crit animation packet marks the
 * entity so the next drop is shown as a crit, and {@code EntityDamageS2CPacket} confirms a hit; on
 * servers that hide other players' health, a confirmed hit with no visible health change shows a
 * small "hit" marker instead (optional). Numbers are pooled; rendering projects them on the HUD.</p>
 */
public final class DamageNumbers extends Module implements IncomingPacketInterceptor {
    private static final String TARGETS = "Targets";
    private static final String LOOK = "Look";
    private static final int MAX_NUMBERS = 96;
    private static final long CRIT_MARK_MS = 400L;
    private static final int HIDDEN_HEALTH_TICKS = 3;

    private final BooleanSetting players = new BooleanSetting("Players", true)
            .setDescription("Shows numbers on other players.").setSection(TARGETS);
    private final BooleanSetting mobs = new BooleanSetting("Mobs", true)
            .setDescription("Shows numbers on mobs and animals.").setSection(TARGETS);
    private final BooleanSetting self = new BooleanSetting("Self", false)
            .setDescription("Shows numbers for damage you take (in third person).").setSection(TARGETS);
    private final BooleanSetting heals = new BooleanSetting("Heals", false)
            .setDescription("Also shows health gained, in Heal Color.").setSection(TARGETS);
    private final BooleanSetting hiddenHealthMarker = new BooleanSetting("Hidden Health Marker", false)
            .setDescription("When the server confirms a hit but hides the player's health, shows a small 'hit' marker.")
            .setSection(TARGETS);
    private final SliderSetting range = new SliderSetting("Range", 48, 8, 128, 0)
            .setDescription("Farthest entity, in blocks, that gets numbers.").setSection(TARGETS);

    private final SliderSetting scale = new SliderSetting("Scale", 1.0, 0.5, 2.5, 2)
            .setDescription("Text size.").setSection(LOOK);
    private final SliderSetting duration = new SliderSetting("Duration", 900, 300, 3000, 0)
            .setDescription("Milliseconds each number stays before it has fully faded.").setSection(LOOK);
    private final SliderSetting rise = new SliderSetting("Rise", 0.9, 0.0, 2.5, 2)
            .setDescription("How many blocks a number floats up over its lifetime.").setSection(LOOK);
    private final ModeSetting format = new ModeSetting("Format", "One Decimal", "One Decimal", "Whole", "Hearts")
            .setDescription("One Decimal: 6.5. Whole: 7. Hearts: 3.3 (health points / 2).").setSection(LOOK);
    private final ModeSetting colorMode = new ModeSetting("Color Mode", "Theme", "Theme", "Custom")
            .setDescription("Theme uses the ClickGUI accent for normal hits; Custom uses Custom Color.").setSection(LOOK);
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(255, 255, 255))
            .setDescription("Normal hit colour when Color Mode is Custom.").setDependsOn("Color Mode:Custom").setSection(LOOK);
    private final ColorSetting critColor = new ColorSetting("Crit Color", new Color(255, 196, 40))
            .setDescription("Colour of critical hits, which also get a star.").setSection(LOOK);
    private final ColorSetting healColor = new ColorSetting("Heal Color", new Color(90, 255, 120))
            .setDescription("Colour of heals.").setDependsOn("Heals").setSection(LOOK);

    /** Client thread only. */
    private final Int2FloatOpenHashMap lastHealth = new Int2FloatOpenHashMap();
    private final Int2LongOpenHashMap critMarks = new Int2LongOpenHashMap();
    private final Int2IntOpenHashMap pendingHits = new Int2IntOpenHashMap();
    private final IntOpenHashSet seen = new IntOpenHashSet();
    private final Number[] numbers = new Number[MAX_NUMBERS];
    private final float[] screen = new float[3];
    private ClientWorld lastWorld;
    private int ticks;

    public DamageNumbers() {
        super("DamageNumbers", "Floating numbers rise off players and mobs when they take damage, with crits in their own "
                + "colour and a star, and optional heals. Visible only to you.", Category.RENDER);
        addSetting(players);
        addSetting(mobs);
        addSetting(self);
        addSetting(heals);
        addSetting(hiddenHealthMarker);
        addSetting(range);
        addSetting(scale);
        addSetting(duration);
        addSetting(rise);
        addSetting(format);
        addSetting(colorMode);
        addSetting(customColor);
        addSetting(critColor);
        addSetting(healColor);
        for (int i = 0; i < MAX_NUMBERS; i++) numbers[i] = new Number();
        lastHealth.defaultReturnValue(Float.NaN);
    }

    @Override
    public boolean rendersInWorld() {
        return false;
    }

    @Override
    public void onDisable() {
        clear();
    }

    @Override
    protected void onRuntimeStateReleased() {
        clear();
    }

    private void clear() {
        lastHealth.clear();
        critMarks.clear();
        pendingHits.clear();
        seen.clear();
        for (Number n : numbers) n.active = false;
        lastWorld = null;
    }

    /** Netty thread: copies ids and hops to the client thread. Never cancels. */
    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        if (packet instanceof EntityAnimationS2CPacket anim && anim.getAnimationId() == EntityAnimationS2CPacket.CRIT) {
            int id = anim.getEntityId();
            mc.execute(() -> {
                if (isEnabled()) critMarks.put(id, System.currentTimeMillis());
            });
        } else if (packet instanceof EntityDamageS2CPacket damage) {
            int id = damage.entityId();
            mc.execute(() -> {
                if (isEnabled()) pendingHits.put(id, ticks);
            });
        }
        return false;
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) return;
        if (mc.world != lastWorld) {
            clear();
            lastWorld = mc.world;
        }
        ticks++;
        long now = System.currentTimeMillis();
        double maxSq = range.getValue() * range.getValue();
        seen.clear();

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity living) || !accepts(e)) continue;
            int id = e.getId();
            seen.add(id);
            float total = living.getHealth() + living.getAbsorptionAmount();
            float prev = lastHealth.put(id, total);
            if (Float.isNaN(prev) || mc.player.squaredDistanceTo(e) > maxSq) continue;
            float delta = prev - total;
            if (delta > 0.05f) {
                boolean crit = now - critMarks.getOrDefault(id, Long.MIN_VALUE / 2) <= CRIT_MARK_MS;
                if (crit) critMarks.remove(id);
                pendingHits.remove(id);
                spawn(living, delta, crit ? Kind.CRIT : Kind.DAMAGE, now);
            } else if (delta < -0.05f && heals.isEnabled() && living.isAlive()) {
                spawn(living, -delta, Kind.HEAL, now);
            }
        }

        // Prune entities that left, and turn stale confirmed hits into markers when health is hidden.
        lastHealth.keySet().removeIf((int id) -> !seen.contains(id));
        if (!pendingHits.isEmpty()) {
            var it = pendingHits.int2IntEntrySet().fastIterator();
            while (it.hasNext()) {
                var entry = it.next();
                if (ticks - entry.getIntValue() < HIDDEN_HEALTH_TICKS) continue;
                it.remove();
                if (!hiddenHealthMarker.isEnabled()) continue;
                Entity e = mc.world.getEntityById(entry.getIntKey());
                if (e instanceof LivingEntity living && accepts(e) && mc.player.squaredDistanceTo(e) <= maxSq) {
                    spawn(living, 0.0f, Kind.MARKER, now);
                }
            }
        }
        if (!critMarks.isEmpty()) critMarks.values().removeIf((long t) -> now - t > CRIT_MARK_MS * 4);
    }

    private boolean accepts(Entity e) {
        if (e == mc.player) return self.isEnabled();
        if (e instanceof PlayerEntity) return players.isEnabled();
        if (!mobs.isEnabled() || e instanceof ArmorStandEntity) return false;
        return e.getType().getSpawnGroup() != SpawnGroup.MISC;
    }

    private void spawn(LivingEntity e, float amount, Kind kind, long now) {
        Number slot = null;
        long oldest = Long.MAX_VALUE;
        for (Number n : numbers) {
            if (!n.active) {
                slot = n;
                break;
            }
            if (n.start < oldest) {
                oldest = n.start;
                slot = n;
            }
        }
        if (slot == null) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double spread = Math.max(0.3, e.getWidth() * 0.6);
        slot.active = true;
        slot.start = now;
        slot.kind = kind;
        slot.x = e.getX() + (rnd.nextDouble() - 0.5) * spread;
        slot.y = e.getY() + e.getHeight() * (0.75 + rnd.nextDouble() * 0.2);
        slot.z = e.getZ() + (rnd.nextDouble() - 0.5) * spread;
        slot.label = label(amount, kind);
    }

    private String label(float amount, Kind kind) {
        if (kind == Kind.MARKER) return "hit";
        String value = switch (format.getCurrentMode()) {
            case "Whole" -> Integer.toString(Math.max(1, Math.round(amount)));
            case "Hearts" -> String.format(Locale.ROOT, "%.1f❤", amount / 2.0f);
            default -> String.format(Locale.ROOT, "%.1f", amount);
        };
        return switch (kind) {
            case CRIT -> "✦" + value;
            case HEAL -> "+" + value;
            default -> value;
        };
    }

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (context == null || mc.textRenderer == null || mc.world == null) return;
        boolean any = false;
        for (Number n : numbers) {
            if (n.active) {
                any = true;
                break;
            }
        }
        if (!any || !ScreenProjector.begin(tickDelta)) return;

        long now = System.currentTimeMillis();
        double dur = Math.max(1.0, duration.getValue());
        float baseScale = (float) scale.getValue();
        float riseBlocks = (float) rise.getValue();
        int normalRgb = (colorMode.is("Custom") ? customColor.getValue().getRGB() : AccentState.accentArgb()) & 0x00FFFFFF;
        int critRgb = critColor.getValue().getRGB() & 0x00FFFFFF;
        int healRgb = healColor.getValue().getRGB() & 0x00FFFFFF;
        Matrix3x2fStack m = context.getMatrices();

        for (Number n : numbers) {
            if (!n.active) continue;
            float t = (float) ((now - n.start) / dur);
            if (t >= 1.0f) {
                n.active = false;
                continue;
            }
            // Ease-out rise, quick pop-in, fade over the last 40%.
            float eased = 1.0f - (1.0f - t) * (1.0f - t);
            if (!ScreenProjector.toGui(n.x, n.y + eased * riseBlocks, n.z, screen)) continue;
            float distance = screen[2];
            float pop = t < 0.08f ? 0.6f + 0.4f * (t / 0.08f) * 1.25f : 1.0f + Math.max(0.0f, 0.1f - (t - 0.08f));
            float size = baseScale * pop * clamp(6.0f / Math.max(distance, 1.0f), 0.55f, 1.6f)
                    * (n.kind == Kind.CRIT ? 1.25f : 1.0f);
            float alpha = t > 0.6f ? 1.0f - (t - 0.6f) / 0.4f : 1.0f;
            int a = Math.max(4, Math.round(alpha * 255.0f));
            int rgb = switch (n.kind) {
                case CRIT -> critRgb;
                case HEAL -> healRgb;
                case MARKER -> 0xBBBBBB;
                default -> normalRgb;
            };
            int width = mc.textRenderer.getWidth(n.label);
            m.pushMatrix();
            m.translate(screen[0], screen[1]);
            m.scale(size, size);
            context.drawTextWithShadow(mc.textRenderer, n.label, -width / 2, -mc.textRenderer.fontHeight / 2,
                    (a << 24) | rgb);
            m.popMatrix();
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private enum Kind { DAMAGE, CRIT, HEAL, MARKER }

    private static final class Number {
        boolean active;
        long start;
        Kind kind = Kind.DAMAGE;
        double x, y, z;
        String label = "";
    }
}
