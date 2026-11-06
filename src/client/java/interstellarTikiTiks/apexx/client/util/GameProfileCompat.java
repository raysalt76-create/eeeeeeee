package interstellarTikiTiks.apexx.client.util;

import com.mojang.authlib.GameProfile;

import java.lang.reflect.Method;
import java.util.UUID;

public final class GameProfileCompat {
    private static final Method ID_METHOD = findAccessor(GameProfile.class, "getId", "id");
    private static final Method NAME_METHOD = findAccessor(GameProfile.class, "getName", "name");

    private GameProfileCompat() {}

    private static Method findAccessor(Class<?> clazz, String legacyName, String modernName) {
        try {
            Method m = clazz.getMethod(legacyName);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
        }

        try {
            Method m = clazz.getMethod(modernName);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
        }

        return null;
    }

    public static UUID getId(GameProfile profile) {
        if (profile == null || ID_METHOD == null) return null;
        try {
            Object value = ID_METHOD.invoke(profile);
            return value instanceof UUID uuid ? uuid : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public static String getName(GameProfile profile) {
        if (profile == null || NAME_METHOD == null) return null;
        try {
            Object value = NAME_METHOD.invoke(profile);
            return value instanceof String s ? s : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}

