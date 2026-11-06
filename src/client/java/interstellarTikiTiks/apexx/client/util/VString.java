package interstellarTikiTiks.apexx.client.util;

/**
 * Lightweight marker wrapper used by the build-time string converter.
 */
public final class VString {
    private final String value;

    public VString(String value) {
        this.value = value;
    }

    public String getString() {
        return value == null ? "" : value;
    }

    public int codePointAt(int index) {
        String safe = getString();
        if (index < 0 || index >= safe.length()) {
            return -1;
        }
        return Character.codePointAt(safe, index);
    }

    public static boolean isSalt(char c) {
        return c == '$' || c == '^' || c == '~' || c == '`';
    }

    public static String decode(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (!isSalt(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
