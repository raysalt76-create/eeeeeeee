package interstellarTikiTiks.apexx.client.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class NativeBridge {

    public static boolean isLoaded() {
        return ObfRuntime.isNativeVmActive();
    }

    private static final java.util.Map<Long, java.security.KeyPair> javaKeyStore = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong javaKeyIdCounter = new java.util.concurrent.atomic.AtomicLong(
            1_000_000L);

    private NativeBridge() {
    }

    private static native Object n0(int opcode, long a, long b, Object c, Object d);

    public static native int nativeVmInt(int opcode, int arg1, int arg2, int arg3);
    public static native long nativeVmLong(int opcode, long arg1, long arg2, long arg3);

    private static @Nullable byte[] dispatchNative(int opcode, long a, long b, Object c, Object d) {
        int expectedHmac = ObfRuntime.getRawSink() & 0xFFFF0000;
        if ((opcode & 0xFFFF0000) != expectedHmac) {
            throw new SecurityException("JNI Flow HMAC validation failed");
        }
        return (byte[]) n0(opcode, a, b, c, d);
    }

        private static final int OP_GEN_AES_KEY = 0x1A2B;
    private static final int OP_GET_TS = 0x3C4D;
    private static final int OP_VALIDATE_TS = 0x5E6F;
    private static final int OP_COMPUTE_HWID = 0x7A8B;
    private static final int OP_HEARTBEAT = 0x9C0D;
    private static final int OP_ATTESTATION = 0xB1E2;
    private static final int OP_GET_METADATA = 0xC200;
    private static final int OP_VERIFY_ECDSA = 0x7DF4;
    private static final int OP_CT_COMPARE = 0xF5A6;
    private static final int OP_EXTRACT_JSON = 0x17B8;
    private static final int OP_GET_SESSION_STATE = 0x39C0;

    

    private static final int OP_DERIVE_BUNDLE_KEY = 0xE001;

    private static final int OP_GEN_TRANS_KEY = 0xA001;
    private static final int OP_GEN_PROOF_KEY = 0xA002;
    private static final int OP_GET_PUB_KEY   = 0xA003;
    private static final int OP_DECRYPT_TRANS = 0xA004;
    private static final int OP_SIGN_PROOF    = 0xA005;

    public static byte[] generateAesSessionKeySafe() {
        if (!isLoaded()) {
            if (ObfRuntime.isNativeVmDisabled()) {
                byte[] key = new byte[32];
                new java.security.SecureRandom().nextBytes(key);
                return key;
            }
            throw new SecurityException("Native security layer mandatory for session key generation");
        }
        try {
            byte[] key = dispatchNative(OP_GEN_AES_KEY | (ObfRuntime.getRawSink() & 0xFFFF0000), 0, 0, null, null);
            if (key != null && key.length == 32) {
                return key;
            }
        } catch (Throwable ignored) {
        }
        throw new SecurityException("Failed to generate native AES session key");
    }

    public static long getTimestampSecondsSafe() {
        if (isLoaded()) {
            try {
                Long res = (Long) n0(OP_GET_TS | (ObfRuntime.getRawSink() & 0xFFFF0000), 0, 0, null, null);
                if (res != null) return res;
            } catch (Throwable ignored) {
            }
        }
        return System.currentTimeMillis() / 1000L;
    }

    public static boolean validateTimestampSafe(long serverTimestamp) {
        if (serverTimestamp <= 0) return false;

        if (isLoaded()) {
            try {
                Boolean res = (Boolean) n0(OP_VALIDATE_TS | (ObfRuntime.getRawSink() & 0xFFFF0000), serverTimestamp, 0, null, null);
                if (res != null) return res;
            } catch (Throwable ignored) {}
        }

        long now = System.currentTimeMillis() / 1000L;
        return Math.abs(serverTimestamp - now) <= 60;
    }

    public static @Nullable String computeNativeHwidSafe() {
        if (!isLoaded()) {
            if (ObfRuntime.isNativeVmDisabled()) {
                String raw = System.getProperty("user.name", "unknown")
                        + "|" + System.getProperty("os.name", "unknown")
                        + "|" + System.getProperty("os.arch", "unknown")
                        + "|" + System.getProperty("user.home", "unknown");
                return hashHwidComponents(raw);
            }
            throw new SecurityException("Native security layer mandatory for HWID computation");
        }
        try {
            Object result = n0(OP_COMPUTE_HWID | (ObfRuntime.getRawSink() & 0xFFFF0000), 0, 0, null, null);
            // Current JNI returns String; accept byte[] too for older installed native libraries.
            String raw = result instanceof String text ? text
                    : result instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : null;
            if (raw != null && !raw.isBlank()) return hashHwidComponents(raw);
        } catch (Throwable ignored) {
        }
        throw new SecurityException("Failed to compute native HWID");
    }

    private static @Nullable String hashHwidComponents(String rawComponents) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("apexx-native-hwid|" + rawComponents).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                int v = b & 0xFF;
                if (v < 16) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.substring(0, 32);
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] heartbeatPulseSafe(@NotNull String sessionToken, long counter) {
        if (!isLoaded()) {
            if (ObfRuntime.isNativeVmDisabled()) {
                byte[] pulse = new byte[8];
                new java.security.SecureRandom().nextBytes(pulse);
                return pulse;
            }
            throw new SecurityException("Native security layer mandatory for heartbeat pulse");
        }
        try {
            byte[] pulse = dispatchNative(OP_HEARTBEAT | (ObfRuntime.getRawSink() & 0xFFFF0000), counter, 0,
                    sessionToken, null);
            if (pulse != null && pulse.length == 8) {
                return pulse;
            }
        } catch (Throwable ignored) {
        }
        throw new SecurityException("Failed to compute native heartbeat pulse");
    }

    public static byte[] attestationPulseSafe(@NotNull String sessionToken, @NotNull String requestNonce,
            long timestampMs,
            @NotNull String attestationNonce) {
        if (!isLoaded()) {
            if (ObfRuntime.isNativeVmDisabled()) {
                byte[] pulse = new byte[16];
                new java.security.SecureRandom().nextBytes(pulse);
                return pulse;
            }
            throw new SecurityException("Native security layer mandatory for attestation pulse");
        }
        try {
            byte[] pulse = dispatchNative(OP_ATTESTATION | (ObfRuntime.getRawSink() & 0xFFFF0000), timestampMs, 0,
                    sessionToken, new String[] { requestNonce, attestationNonce });
            if (pulse != null && pulse.length == 16) {
                return pulse;
            }
        } catch (Throwable ignored) {
        }
        throw new SecurityException("Failed to compute native attestation pulse");
    }

    private static long rotl64(long v, int r) {

        r &= 63;
        return (v << r) | (v >>> (64 - r));
    }

    private static long rotr64(long v, int r) {
        r &= 63;
        return (v >>> r) | (v << (64 - r));
    }

    /** Drops every in-memory key pair of the Java fallback key store (session end / self-destruct). */
    public static void clearJavaKeyStore() {
        javaKeyStore.clear();
    }

    private static long javaGenerateRsaKeyPair() {

        try {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, new java.security.SecureRandom());
            java.security.KeyPair kp = kpg.generateKeyPair();
            long id = javaKeyIdCounter.getAndIncrement();
            javaKeyStore.put(id, kp);
            return id;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long javaGenerateEcKeyPair() {
        try {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("EC");
            kpg.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"), new java.security.SecureRandom());
            java.security.KeyPair kp = kpg.generateKeyPair();
            long id = javaKeyIdCounter.getAndIncrement();
            javaKeyStore.put(id, kp);
            return id;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static @Nullable String javaGetPublicKeyPem(long keyId) {
        java.security.KeyPair kp = javaKeyStore.get(keyId);
        if (kp == null) {
            return null;
        }
        byte[] encoded = kp.getPublic().getEncoded();
        String b64 = java.util.Base64.getEncoder().encodeToString(encoded);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END PUBLIC KEY-----");
        return sb.toString();
    }

    private static @Nullable byte[] javaDecryptTransport(long keyId, byte[] data) {
        java.security.KeyPair kp = javaKeyStore.get(keyId);
        if (kp == null || data == null) {
            return null;
        }
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding");
            javax.crypto.spec.OAEPParameterSpec oaepParams = new javax.crypto.spec.OAEPParameterSpec(
                    "SHA-256", "MGF1", new java.security.spec.MGF1ParameterSpec("SHA-256"),
                    javax.crypto.spec.PSource.PSpecified.DEFAULT);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, kp.getPrivate(), oaepParams);
            return cipher.doFinal(data);
        } catch (Exception e) {
            try {
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, kp.getPrivate());
                return cipher.doFinal(data);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static @Nullable byte[] javaSignProof(long keyId, byte[] data) {
        java.security.KeyPair kp = javaKeyStore.get(keyId);
        if (kp == null || data == null) {
            return null;
        }
        try {
            String algo = kp.getPrivate().getAlgorithm();
            boolean isEc = "EC".equals(algo);
            String sigAlgo = isEc ? "SHA256withECDSA" : "SHA256withRSA";
            java.security.Signature sig = java.security.Signature.getInstance(sigAlgo);
            sig.initSign(kp.getPrivate());
            sig.update(data);
            byte[] signature = sig.sign();
            if (isEc) {
                return derToP1363(signature, 64);
            }
            return signature;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] derToP1363(byte[] der, int outputLen) {
        if (der == null || der.length < 8 || der[0] != 0x30)
            return der;
        try {
            int offset = 2;
            if ((der[1] & 0x80) != 0)
                offset += (der[1] & 0x7F);

            if (der[offset++] != 0x02)
                return der;
            int rLen = der[offset++];
            int rStart = offset;
            offset += rLen;

            if (der[offset++] != 0x02)
                return der;
            int sLen = der[offset++];
            int sStart = offset;

            byte[] raw = new byte[outputLen];
            int half = outputLen / 2;

            int rCopyLen = Math.min(rLen, half);
            int rOffset = rLen > half ? rLen - half : 0;
            if (der[rStart] == 0x00 && rLen > half) {
                rStart++;
                rLen--;
                rOffset = 0;
                rCopyLen = Math.min(rLen, half);
            }
            System.arraycopy(der, rStart + rOffset, raw, half - rCopyLen, rCopyLen);

            int sCopyLen = Math.min(sLen, half);
            int sOffset = sLen > half ? sLen - half : 0;
            if (der[sStart] == 0x00 && sLen > half) {
                sStart++;
                sLen--;
                sOffset = 0;
                sCopyLen = Math.min(sLen, half);
            }
            System.arraycopy(der, sStart + sOffset, raw, outputLen - sCopyLen, sCopyLen);

            return raw;
        } catch (Exception e) {
            return der;
        }
    }

    public static long generateTransportKeyPairSafe() {
        if (isLoaded()) {
            try {
                byte[] res = dispatchNative(OP_GEN_TRANS_KEY | (ObfRuntime.getRawSink() & 0xFFFF0000), 0, 0, null,
                        null);
                if (res != null && res.length == 8) {
                    return java.nio.ByteBuffer.wrap(res).getLong();
                }
            } catch (Throwable ignored) {
            }
        }
        return javaGenerateRsaKeyPair();
    }

    public static long generateProofKeyPairSafe() {
        if (isLoaded()) {
            try {
                byte[] res = dispatchNative(OP_GEN_PROOF_KEY | (ObfRuntime.getRawSink() & 0xFFFF0000), 0, 0, null,
                        null);
                if (res != null && res.length == 8) {
                    return java.nio.ByteBuffer.wrap(res).getLong();
                }
            } catch (Throwable ignored) {
            }
        }
        return javaGenerateEcKeyPair();
    }

    public static @Nullable String getPublicKeySafe(long keyId) {
        if (isLoaded()) {
            try {
                byte[] res = dispatchNative(OP_GET_PUB_KEY | (ObfRuntime.getRawSink() & 0xFFFF0000), keyId, 0, null,
                        null);
                if (res != null) {
                    return new String(res, StandardCharsets.UTF_8);
                }
            } catch (Throwable ignored) {
            }
        }
        return javaGetPublicKeyPem(keyId);
    }

    public static @Nullable byte[] decryptTransportSafe(long keyId, @Nullable byte[] data) {
        if (isLoaded()) {
            try {
                byte[] result = dispatchNative(OP_DECRYPT_TRANS | (ObfRuntime.getRawSink() & 0xFFFF0000), keyId, 0,
                        data, null);
                if (result != null && result.length > 0) {
                    return result;
                }
            } catch (Throwable ignored) {
            }
        }
        return javaDecryptTransport(keyId, data);
    }

    public static @Nullable byte[] signProofSafe(long keyId, @Nullable byte[] data) {
        if (isLoaded()) {
            try {
                byte[] result = dispatchNative(OP_SIGN_PROOF | (ObfRuntime.getRawSink() & 0xFFFF0000), keyId, 0, data,
                        null);
                if (result != null && result.length > 0) {
                    return result;
                }
            } catch (Throwable ignored) {
            }
        }
        return javaSignProof(keyId, data);
    }

    public static boolean constantTimeEqualsSafe(byte[] a, byte[] b) {
        if (isLoaded()) {
            try {
                return "true".equals(n0(OP_CT_COMPARE | (ObfRuntime.getRawSink() & 0xFFFF0000), 0, 0, a, b));
            } catch (Throwable ignored) {
            }
        }
        return a != null && b != null && java.security.MessageDigest.isEqual(a, b);
    }

    public static @Nullable String extractJsonFieldSafe(String json, String key) {
        if (isLoaded()) {
            try {
                return (String) n0(OP_EXTRACT_JSON | (ObfRuntime.getRawSink() & 0xFFFF0000), 0, 0, json, key);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static long getNativeSessionStateSafe(long salt) {
        if (isLoaded()) {
            try {
                Long res = (Long) n0(OP_GET_SESSION_STATE | (ObfRuntime.getRawSink() & 0xFFFF0000), salt, 0, null, null);
                if (res != null) return res;
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static void scrub(byte[] data) {
        if (data != null) {
            java.util.Arrays.fill(data, (byte) 0);
        }
    }

    public static boolean verifyAuthProofSignatureSafe(byte[] data, byte[] signature) {

        if (isLoaded()) {
            try {
                if ("true".equals(n0(OP_VERIFY_ECDSA | (ObfRuntime.getRawSink() & 0xFFFF0000), 0, 0, data, signature))) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            String pubKeyPem = "-----BEGIN PUBLIC KEY-----\n" +
                    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEYihvVImIvm/NXaQ5q6Lgm36HG6cK\n" +
                    "3MGUXnbMgm14wWcbgs0d8e1/PKTOT2KmioqjXm/s6PnhKgyrZ5OQOvXVfA==\n" +
                    "-----END PUBLIC KEY-----";

            String b64 = pubKeyPem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = java.util.Base64.getDecoder().decode(b64);
            java.security.spec.X509EncodedKeySpec keySpec = new java.security.spec.X509EncodedKeySpec(der);
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("EC");
            java.security.PublicKey pubKey = kf.generatePublic(keySpec);

            return interstellarTikiTiks.apexx.client.security.SecurityUtils.verifyAuthProofSignature(pubKey, data, signature);
        } catch (Exception e) {
            return false;
        }
    }

    









    public static @Nullable byte[] deriveBundleKeySafe(
            @NotNull String sessionToken,
            @NotNull String deviceId,
            @NotNull String bundleId) {
        if (isLoaded()) {
            try {
                byte[] key = (byte[]) n0(
                        OP_DERIVE_BUNDLE_KEY | (ObfRuntime.getRawSink() & 0xFFFF0000),
                        0L, 0L,
                        sessionToken,
                        new String[]{ deviceId, bundleId });
                if (key != null && key.length == 32) return key;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static @Nullable String getMetadataSafe(int id) {
        if (isLoaded()) {
            try {
                return (String) n0(0xC200 | (ObfRuntime.getRawSink() & 0xFFFF0000), id, 0, null, null);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

}
