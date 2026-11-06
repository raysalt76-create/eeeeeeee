package interstellarTikiTiks.apexx.client.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class NativeCoreStringKeyTest {

    @Test
    public void testKeyWordMatchesSchedule() {
        long master = CryptoVault.nativeMaster();
        int classId = 1822224069; // GpuUiRenderer
        int nonce = -867250293;

        // StringEncryptor pushes nonce then classId to NativeCore.keyWord(int, int)
        int runtimeKey = NativeCore.keyWord(nonce, classId);

        // Expected key computed with master, classId, nonce
        int expectedKey = NativeCore.mirrorKeyWord(master, classId, nonce);

        assertEquals(expectedKey, runtimeKey, "NativeCore.keyWord(nonce, classId) must yield mirrorKeyWord(master, classId, nonce)");
    }
}
