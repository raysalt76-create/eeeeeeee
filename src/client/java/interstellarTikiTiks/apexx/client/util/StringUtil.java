package interstellarTikiTiks.apexx.client.util;

import java.nio.charset.StandardCharsets;

public class StringUtil {

    static int POLY_SEED = 0x6A09E667;

    public static String decrypt(byte[] data, int baseKey) {
        byte[] d = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            d[i] = (byte) (data[i] ^ ((baseKey + i) % 256));
        }
        return new String(d, StandardCharsets.UTF_8);
    }

    public static String decrypt(byte[] data, int baseKey, int salt, int marker) {
        int expectedChecksum = marker & 0x0FFFFFFF;

        byte[] decoded = decodeV6(data, baseKey, salt, marker);

        int checksum = checksumV3(decoded, data, baseKey, salt, marker) & 0x0FFFFFFF;
        if (checksum != expectedChecksum) {
            ObfRuntime.rawNoise(baseKey ^ marker, salt ^ expectedChecksum);
            return "";
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static byte[] decodeV6(byte[] data, int baseKey, int salt, int marker) {
        if (data == null || data.length < 4) {
            return new byte[0];
        }
        int schemeMarker = marker & 0xF0000000;

        int nonce = ((data[0] & 0xFF) << 24)
                | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8)
                | (data[3] & 0xFF);
        int outLen = data.length - 4;
        byte[] out = new byte[outLen];

        int s0 = mixSeed(baseKey ^ nonce, salt ^ schemeMarker ^ 0x9E3779B9);
        int s1 = mixSeed(baseKey ^ 0x6A09E667, salt ^ nonce ^ 0x3C6EF372);
        int s2 = mixSeed(schemeMarker ^ 0xBB67AE85, baseKey ^ salt ^ 0xA54FF53A);
        int carry = nonce ^ outLen ^ 0x7F4A7C15;

        for (int i = 0; i < outLen; i++) {
            s0 = nextState(s0 + i + carry);
            s1 = nextState(s1 ^ Integer.rotateLeft(s0, (i + 3) & 31));
            s2 = nextState(s2 + Integer.rotateRight(s1, (i + 11) & 31));

            int laneA = s0 ^ Integer.rotateLeft(s2, i & 31);
            int laneB = s1 + Integer.rotateRight(s0, (i + 7) & 31);
            int stream = Integer.rotateLeft(laneA, 5) ^ Integer.rotateRight(laneB, 3) ^ (i * 0x45D9F3B);

            int enc = data[i + 4] & 0xFF;
            int plain = enc ^ (stream & 0xFF);
            out[i] = (byte) plain;

            carry = (carry + plain + (enc << (i & 3))) ^ Integer.rotateLeft(stream, (i + 13) & 31);
        }

        return out;
    }

    private static int checksumV3(byte[] plain, byte[] enc, int baseKey, int salt, int marker) {
        int schemeMarker = marker & 0xF0000000;
        int c = 0x243F6A88 ^ baseKey ^ Integer.rotateLeft(salt, 11) ^ schemeMarker;
        for (int i = 0; i < plain.length; i++) {
            int pv = plain[i] & 0xFF;
            c = Integer.rotateLeft(c ^ pv ^ i, 7) + 0x9E3779B9;
        }
        for (byte b : enc) {
            c = Integer.rotateLeft(c + (b & 0xFF), 5) ^ 0x7F4A7C15;
        }
        return c ^ (plain.length * 0x45D9F3B);
    }

    private static int mixSeed(int baseKey, int salt) {
        return baseKey ^ Integer.rotateLeft(salt, 13) ^ POLY_SEED ^ 0x9E3779B9;
    }

    private static int nextState(int value) {
        int x = value;
        x ^= (x << 13);
        x ^= (x >>> 17);
        x ^= (x << 5);
        return x;
    }
}
