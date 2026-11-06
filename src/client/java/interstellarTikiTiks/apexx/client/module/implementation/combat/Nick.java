package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.NicknameProvider;
import interstellarTikiTiks.apexx.client.module.StringSetting;

public class Nick extends Module implements NicknameProvider {

    public final StringSetting nickname = new StringSetting("Nickname", "Player")
            .setDescription("Custom display name shown client-side in nametags, chat, and tab list.");

    public Nick() {
        super("Nick", "Replaces your name with the nickname below on your own screen only (nametags, chat and tab list). Other players and the server still see your real name; useful for recording or streaming.", Category.PLAYER);
        addSetting(nickname);
    }

    public String getSafeNickname(String originalName) {

        if (!this.isEnabled()) {
            return originalName;
        }

        String nick = nickname.getValue();

        if (nick == null || nick.trim().isEmpty()) {
            return originalName;
        }

        return nick;
    }

}
