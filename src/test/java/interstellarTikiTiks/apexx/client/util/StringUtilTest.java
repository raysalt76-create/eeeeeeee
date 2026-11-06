package interstellarTikiTiks.apexx.client.util;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

public class StringUtilTest {

    // Helper method to encrypt a string using the same XOR logic as decrypt
    private byte[] encrypt(String text, int baseKey) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        byte[] e = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            e[i] = (byte) (data[i] ^ ((baseKey + i) % 256));
        }
        return e;
    }

    @Test
    public void testDecryptBasicString() {
        String originalText = "Hello, Apexx!";
        int baseKey = 123;
        byte[] encryptedData = encrypt(originalText, baseKey);
        String decryptedText = StringUtil.decrypt(encryptedData, baseKey);
        assertEquals(originalText, decryptedText, "Decrypted text should match the original basic string.");
    }

    @Test
    public void testDecryptEmptyString() {
        String originalText = "";
        int baseKey = 456;
        byte[] encryptedData = encrypt(originalText, baseKey);
        String decryptedText = StringUtil.decrypt(encryptedData, baseKey);
        assertEquals(originalText, decryptedText, "Decrypted text should match the original empty string.");
    }

    @Test
    public void testDecryptSpecialCharacters() {
        String originalText = "Hello 🌍! Special chars: !@#$%^&*()_+~`|}{[]:;?><,./";
        int baseKey = 789;
        byte[] encryptedData = encrypt(originalText, baseKey);
        String decryptedText = StringUtil.decrypt(encryptedData, baseKey);
        assertEquals(originalText, decryptedText, "Decrypted text should handle special characters and UTF-8.");
    }

    @Test
    public void testDecryptWithWrongKey() {
        String originalText = "Secret message";
        int correctKey = 42;
        int wrongKey = 43;
        byte[] encryptedData = encrypt(originalText, correctKey);
        String decryptedText = StringUtil.decrypt(encryptedData, wrongKey);
        assertNotEquals(originalText, decryptedText, "Decrypted text with a wrong key should not match the original string.");
    }
}
