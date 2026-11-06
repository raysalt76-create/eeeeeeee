package interstellarTikiTiks.apexx.client.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The canonical filter tags a config can carry. Multi-select: a config may have any combination.
 * Kept in one place so the save UI, the community filter and the cloud layer all agree on the set
 * and the exact casing (the backend mirrors this list in functions/_shared / api).
 */
public final class ConfigTags {
    public static final List<String> ALL = List.of(
            "Legit", "Blatant", "Crystal", "Mace", "SpearMace", "Sword", "SMP");

    private ConfigTags() { }

    /** The canonical spelling of a tag (case-insensitive match), or null if it is not a known tag. */
    public static String canonical(String tag) {
        if (tag == null) return null;
        for (String known : ALL) {
            if (known.equalsIgnoreCase(tag.trim())) return known;
        }
        return null;
    }

    /** Filter an arbitrary collection down to the known tags, de-duplicated and in canonical casing. */
    public static List<String> clean(Collection<String> tags) {
        List<String> out = new ArrayList<>();
        if (tags == null) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String tag : tags) {
            String canonical = canonical(tag);
            if (canonical != null && seen.add(canonical)) out.add(canonical);
        }
        return out;
    }
}
