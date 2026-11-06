package interstellarTikiTiks.apexx.client.util;

import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SensitiveStrings {
    

    

    

    

    private static final Map<Integer, SoftReference<String>> CACHE = new ConcurrentHashMap<>();

    private SensitiveStrings() {
    }

    public static String legacy(byte[] data, int baseKey, int slot) {
        SoftReference<String> ref = CACHE.get(slot);
        String cached = (ref != null) ? ref.get() : null;
        if (cached != null) return cached;
        String result = decodeLegacy(data, preserveInt(baseKey, slot, data.length));
        CACHE.put(slot, new SoftReference<>(result));
        return result;
    }

    public static String sealed(byte[] data, int baseKey, int salt, int marker, int slot) {
        SoftReference<String> ref = CACHE.get(slot);
        String cached = (ref != null) ? ref.get() : null;
        if (cached != null) return cached;
        String result = StringUtil.decrypt(
                data,
                preserveInt(baseKey, slot ^ 0x13579BDF, data.length),
                preserveInt(salt,    slot ^ 0x2468ACE1, marker),
                preserveInt(marker,  slot ^ 0x7F4A7C15, salt ^ data.length));
        CACHE.put(slot, new SoftReference<>(result));
        return result;
    }

    private static String decodeLegacy(byte[] data, int baseKey) {
        byte[] out = new byte[data.length];
        int rolling = ObfRuntime.vmInt(6, baseKey, data.length, 0x6A09E667);
        for (int i = 0; i < data.length; i++) {
            int lane = ObfRuntime.vmInt(4, rolling ^ i, i & 31, 0x3C6EF372 ^ baseKey);
            int dynamicKey = ((baseKey + i) ^ (lane & 0)) & 0xFF;
            out[i] = (byte) (data[i] ^ dynamicKey);
            rolling = ObfRuntime.vmInt(7, rolling, dynamicKey, i ^ data.length);
        }
        return new String(out, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int preserveInt(int value, int salt, int mix) {
        int mask = ObfRuntime.vmInt(7,
                value ^ 0x9E3779B9,
                salt ^ 0x3C6EF372,
                mix ^ 0x6A09E667);
        return ObfRuntime.vmInt(1, mask, mask ^ value, 0);
    }
}
