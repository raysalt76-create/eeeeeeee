package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.enemy.EnemyManager;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.GlowDecisionProvider;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import java.awt.Color;

/**
 * Vanilla glowing outline on friends and on players in the enemy list, each in its own colour.
 * Client-side only: {@code EntityGlowingMixin} asks {@link #glowColorFor} from {@code isGlowing} and
 * {@code getTeamColorValue}, so the outline uses vanilla's own glow pass and nothing is sent anywhere.
 */
public final class FriendGlow extends Module implements GlowDecisionProvider {

    private final BooleanSetting friends = new BooleanSetting("Friends", true)
            .setDescription("Outlines players on your friends list.");
    private final ModeSetting friendColorMode = new ModeSetting("Friend Color Mode", "Theme", "Theme", "Custom")
            .setDescription("Theme uses the ClickGUI accent for friends; Custom uses Friend Color.")
            .setDependsOn("Friends");
    private final ColorSetting friendColor = new ColorSetting("Friend Color", new Color(85, 255, 140))
            .setDescription("Friend outline colour when Friend Color Mode is Custom.")
            .setDependsOn("Friend Color Mode:Custom");
    private final BooleanSetting enemies = new BooleanSetting("Enemies", true)
            .setDescription("Outlines players on your enemy list.");
    private final ColorSetting enemyColor = new ColorSetting("Enemy Color", new Color(255, 60, 60))
            .setDescription("Enemy outline colour.")
            .setDependsOn("Enemies");
    private final BooleanSetting throughInvisibility = new BooleanSetting("Invisible Players", false)
            .setDescription("Also outlines friends and enemies while they are invisible.");

    public FriendGlow() {
        super("FriendGlow", "Gives friends a glowing outline in one colour and players on your enemy list another, "
                + "visible through walls, so you never hit a teammate in a crowd. Visible only to you.", Category.RENDER);
        addSetting(friends);
        addSetting(friendColorMode);
        addSetting(friendColor);
        addSetting(enemies);
        addSetting(enemyColor);
        addSetting(throughInvisibility);
    }

    @Override
    public boolean rendersInWorld() {
        return false;
    }

    @Override
    public boolean shouldApplyGlow() {
        return false;
    }

    /** 0 means "no glow", so pure black is nudged to near-black. */
    private static int nonZero(int rgb) {
        return rgb == 0 ? 0x010101 : rgb;
    }

    @Override
    public int glowColorFor(Entity entity) {
        if (!(entity instanceof PlayerEntity player) || player == mc.player || !player.isAlive()) return 0;
        if (player.isInvisible() && !throughInvisibility.isEnabled()) return 0;
        if (friends.isEnabled() && FriendManager.getInstance().isFriend(player.getUuid())) {
            int rgb = friendColorMode.is("Custom") ? friendColor.getValue().getRGB() : AccentState.accentArgb();
            return nonZero(rgb & 0x00FFFFFF);
        }
        if (enemies.isEnabled()) {
            EnemyManager em = EnemyManager.getInstance();
            if (em != null && em.isEnemy(player.getUuid())) {
                return nonZero(enemyColor.getValue().getRGB() & 0x00FFFFFF);
            }
        }
        return 0;
    }
}
