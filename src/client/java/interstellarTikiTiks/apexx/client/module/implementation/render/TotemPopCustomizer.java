package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.hud.TotemPopManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.TotemPopAware;
import interstellarTikiTiks.apexx.client.module.hooks.TotemPopVisualProvider;
import interstellarTikiTiks.apexx.client.util.Notifier;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.awt.Color;

/**
 * Customises how totem pops look and sound on your screen and reports pop counts. Vanilla effects are
 * adjusted through the totem-pop seams in the network handler; nothing is sent to the server.
 */
public final class TotemPopCustomizer extends Module implements TotemPopAware, TotemPopVisualProvider {
    private static final String PARTICLES = "Particles";
    private static final String FLOATING = "Floating Totem";
    private static final String SOUND = "Sound";
    private static final String MESSAGES = "Messages";

    private final ModeSetting particleMode = new ModeSetting("Particles", "Vanilla", "Vanilla", "Custom Color", "None")
            .setDescription("Vanilla keeps the yellow-green burst, Custom Color paints every totem particle in the "
                    + "colour below, None removes the burst.")
            .setSection(PARTICLES);
    private final ColorSetting particleColor = new ColorSetting("Particle Color", new Color(255, 70, 90))
            .setDescription("Colour used for totem particles when Particles is Custom Color.")
            .setDependsOn("Particles:Custom Color")
            .setSection(PARTICLES);
    private final SliderSetting particleAmount = new SliderSetting("Particle Amount", 100, 10, 300, 0)
            .setDescription("Size of the particle burst in percent of vanilla (how long the burst keeps spawning).")
            .setDependsOn("Particles:Vanilla|Custom Color")
            .setSection(PARTICLES);

    private final ModeSetting floatingMode = new ModeSetting("Floating Totem", "Vanilla", "Vanilla", "Scaled", "Hidden")
            .setDescription("The big totem that flies across your screen when your own totem pops: keep it, resize "
                    + "it, or hide it.")
            .setSection(FLOATING);
    private final SliderSetting floatingScale = new SliderSetting("Floating Scale", 0.6, 0.2, 2.0, 2)
            .setDescription("Size of the floating totem when Floating Totem is Scaled; 1 is vanilla.")
            .setDependsOn("Floating Totem:Scaled")
            .setSection(FLOATING);

    private final ModeSetting soundMode = new ModeSetting("Pop Sound", "Vanilla", "Vanilla", "Custom", "None")
            .setDescription("Vanilla keeps the totem sound, Custom replaces it with the sound below, None mutes it.")
            .setSection(SOUND);
    private final ModeSetting customSound = new ModeSetting("Custom Sound", "Ding", "Ding", "Bell", "Anvil", "Glass",
            "Chime", "Level Up")
            .setDescription("Sound played for each pop when Pop Sound is Custom.")
            .setDependsOn("Pop Sound:Custom")
            .setSection(SOUND);
    private final SliderSetting volume = new SliderSetting("Volume", 1.0, 0.1, 2.0, 2)
            .setDescription("Loudness of the custom pop sound.")
            .setDependsOn("Pop Sound:Custom")
            .setSection(SOUND);
    private final SliderSetting pitch = new SliderSetting("Pitch", 1.0, 0.5, 2.0, 2)
            .setDescription("Pitch of the custom pop sound; higher is sharper.")
            .setDependsOn("Pop Sound:Custom")
            .setSection(SOUND);

    private final BooleanSetting notify = new BooleanSetting("Notification", true)
            .setDescription("Shows a pop-up with the player's name and how many totems they have popped in this "
                    + "world. Needs the Notifications module.")
            .setSection(MESSAGES);
    private final BooleanSetting chat = new BooleanSetting("Chat Message", false)
            .setDescription("Also prints the pop count in your chat (only you see it).")
            .setSection(MESSAGES);
    private final BooleanSetting announceSelf = new BooleanSetting("Your Pops", true)
            .setDescription("Report your own totem pops.")
            .setSection(MESSAGES);
    private final BooleanSetting announceOthers = new BooleanSetting("Others' Pops", true)
            .setDescription("Report other players' totem pops.")
            .setSection(MESSAGES);

    public TotemPopCustomizer() {
        super("TotemPopCustomizer", "Changes how totem pops look and sound for you: particle colour and amount, "
                + "the floating totem's size, the pop sound, and a pop-count notification or chat line for every "
                + "player. Visible only to you.", Category.RENDER);
        addSetting(particleMode);
        addSetting(particleColor);
        addSetting(particleAmount);
        addSetting(floatingMode);
        addSetting(floatingScale);
        addSetting(soundMode);
        addSetting(customSound);
        addSetting(volume);
        addSetting(pitch);
        addSetting(notify);
        addSetting(chat);
        addSetting(announceSelf);
        addSetting(announceOthers);
    }

    @Override
    public boolean rendersInWorld() {
        return false;
    }

    // ---- TotemPopVisualProvider -------------------------------------------------------------------

    @Override
    public int totemParticleTicks(Entity entity, int vanillaTicks) {
        if (particleMode.is("None")) return 0;
        return Math.max(1, (int) Math.round(vanillaTicks * particleAmount.getValue() / 100.0));
    }

    @Override
    public boolean suppressTotemSound(Entity entity) {
        return !soundMode.is("Vanilla");
    }

    @Override
    public Color totemParticleColor() {
        return particleMode.is("Custom Color") ? particleColor.getValue() : null;
    }

    @Override
    public boolean hideFloatingTotem() {
        return floatingMode.is("Hidden");
    }

    @Override
    public float floatingTotemScale() {
        return floatingMode.is("Scaled") ? (float) floatingScale.getValue() : 1.0f;
    }

    // ---- TotemPopAware (client thread, before vanilla's pop effects) --------------------------------

    @Override
    public void onTotemPop(PlayerEntity player) {
        if (player == null || mc.world == null) return;
        if (soundMode.is("Custom")) {
            SoundEvent event = selectedSound();
            mc.world.playSoundClient(player.getX(), player.getY(), player.getZ(), event, SoundCategory.PLAYERS,
                    (float) volume.getValue(), (float) pitch.getValue(), false);
        }

        boolean self = mc.player != null && player.getUuid().equals(mc.player.getUuid());
        if (self ? !announceSelf.isEnabled() : !announceOthers.isEnabled()) return;
        if (!notify.isEnabled() && !chat.isEnabled()) return;

        int pops = Math.max(1, TotemPopManager.getInstance().getPopCount(player));
        String who = self ? "You" : player.getName().getString();
        String line = who + (self ? " have" : " has") + " popped " + pops + (pops == 1 ? " totem" : " totems");
        if (notify.isEnabled()) Notifier.warn("Totem Pop", line);
        if (chat.isEnabled() && mc.inGameHud != null) {
            mc.inGameHud.getChatHud().addMessage(Text.literal(line));
        }
    }

    private SoundEvent selectedSound() {
        return switch (customSound.getCurrentMode()) {
            case "Bell" -> SoundEvents.BLOCK_BELL_USE;
            case "Anvil" -> SoundEvents.BLOCK_ANVIL_LAND;
            case "Glass" -> SoundEvents.BLOCK_GLASS_BREAK;
            case "Chime" -> SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME;
            case "Level Up" -> SoundEvents.ENTITY_PLAYER_LEVELUP;
            default -> SoundEvents.BLOCK_NOTE_BLOCK_PLING.value();
        };
    }
}
