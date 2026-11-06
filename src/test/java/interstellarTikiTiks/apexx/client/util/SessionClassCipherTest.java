package interstellarTikiTiks.apexx.client.util;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SessionClassCipherTest {
    @Test void freshNoncesAndLoaderKeysPreserveBytes() {
        var cipher = new SessionClassCipher();
        byte[] plain = "class body fixture".getBytes(StandardCharsets.UTF_8);
        byte[] one = cipher.seal("fixture.Module", plain), two = cipher.seal("fixture.Module", plain);
        assertFalse(Arrays.equals(one, two));
        assertArrayEquals(plain, cipher.open("fixture.Module", one));
        assertThrows(SecurityException.class, () -> new SessionClassCipher().open("fixture.Module", one));
        assertThrows(SecurityException.class, () -> cipher.open("fixture.Other", one));
    }

    @Test void corruptionTruncationAndDestroyedKeysFailClosed() {
        var cipher = new SessionClassCipher();
        byte[] sealed = cipher.seal("fixture.Module", new byte[1024]);
        byte[] corrupt = sealed.clone(); corrupt[corrupt.length-1] ^= 1;
        assertThrows(SecurityException.class, () -> cipher.open("fixture.Module", corrupt));
        assertThrows(SecurityException.class, () -> cipher.open("fixture.Module", Arrays.copyOf(sealed, 10)));
        cipher.destroy();
        assertThrows(SecurityException.class, () -> cipher.open("fixture.Module", sealed));
        cipher.seal("fixture.Module", new byte[1024]);
        assertThrows(SecurityException.class, () -> cipher.open("fixture.Module", sealed));
    }
}
