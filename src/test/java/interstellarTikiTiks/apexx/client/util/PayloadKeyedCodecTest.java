package interstellarTikiTiks.apexx.client.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PayloadKeyedCodecTest {
    private static final String NAME = "a/b/Some.class";
    private static byte[] key(int fill) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) fill);
        return k;
    }

    @AfterEach void clear() { PayloadKeyring.clear(); }

    @Test void roundTripsWithTheSessionKey() {
        byte[] plain = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 1, 2, 3, 4};
        byte[] encoded = PayloadClassCodec.encodeKeyed(NAME, plain, key(7));
        assertTrue(PayloadClassCodec.isEncoded(encoded));
        assertTrue(PayloadClassCodec.isKeyed(encoded));
        PayloadKeyring.set(key(7));
        assertArrayEquals(plain, PayloadClassCodec.decode(NAME, encoded));
    }

    @Test void failsClosedWithoutOrWithTheWrongKey() {
        byte[] encoded = PayloadClassCodec.encodeKeyed(NAME, new byte[] {1, 2, 3}, key(7));
        assertThrows(SecurityException.class, () -> PayloadClassCodec.decode(NAME, encoded));
        PayloadKeyring.set(key(8));
        assertThrows(SecurityException.class, () -> PayloadClassCodec.decode(NAME, encoded));
    }

    @Test void entryIsBoundToItsNameAndToTamperingIsDetected() {
        byte[] encoded = PayloadClassCodec.encodeKeyed(NAME, new byte[] {1, 2, 3}, key(7));
        PayloadKeyring.set(key(7));
        assertThrows(SecurityException.class, () -> PayloadClassCodec.decode("a/b/Other.class", encoded));
        encoded[encoded.length - 1] ^= 1;
        assertThrows(SecurityException.class, () -> PayloadClassCodec.decode(NAME, encoded));
    }
}
