package interstellarTikiTiks.apexx.client.util;

import java.util.Arrays;

/**
 * Holds the release key the service handed to this authorized session, in memory only. The key never
 * appears in the loader or the archive; it arrives in the signed manifest of an authenticated
 * request and is wiped when the client unloads or the session ends.
 */
public final class PayloadKeyring {
    private static final Object LOCK = new Object();
    private static byte[] key;

    private PayloadKeyring() {}

    /** Stores a copy of a 32-byte release key; any other length clears the keyring. */
    public static void set(byte[] releaseKey) {
        synchronized (LOCK) {
            if (key != null) Arrays.fill(key, (byte) 0);
            key = releaseKey != null && releaseKey.length == 32 ? Arrays.copyOf(releaseKey, 32) : null;
        }
    }

    /** A private copy the caller must wipe, or {@code null} when no key is held. */
    public static byte[] copy() {
        synchronized (LOCK) {
            return key == null ? null : Arrays.copyOf(key, key.length);
        }
    }

    public static void clear() {
        set(null);
    }
}
