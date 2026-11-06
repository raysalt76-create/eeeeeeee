package interstellarTikiTiks.apexx.client.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/** Authenticated encryption for class bodies retained by one payload loader, never a session grant. */
final class SessionClassCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 12;
    private byte[] key;

    synchronized byte[] seal(String name, byte[] bytes) {
        if (key == null) {
            key = new byte[32];
            RANDOM.nextBytes(key);
        }
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, name, nonce);
            byte[] result = new byte[NONCE_BYTES + cipher.getOutputSize(bytes.length)];
            System.arraycopy(nonce, 0, result, 0, NONCE_BYTES);
            cipher.doFinal(bytes, 0, bytes.length, result, NONCE_BYTES);
            return result;
        } catch (GeneralSecurityException failure) {
            throw new SecurityException("Cannot protect retained class bytes", failure);
        }
    }

    synchronized byte[] open(String name, byte[] sealed) {
        if (key == null || sealed.length < NONCE_BYTES + 16) throw new SecurityException("Missing class key or truncated body");
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, name, Arrays.copyOf(sealed, NONCE_BYTES));
            return cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
        } catch (GeneralSecurityException failure) {
            throw new SecurityException("Retained class authentication failed", failure);
        }
    }

    private Cipher cipher(int mode, String name, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    synchronized void destroy() {
        if (key != null) Arrays.fill(key, (byte) 0);
        key = null;
    }
}
