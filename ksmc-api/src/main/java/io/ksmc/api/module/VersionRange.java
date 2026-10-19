package io.ksmc.api.module;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Plage de versions façon npm : {@code *}, {@code 1.2.3} (exacte), {@code ^1.2}, {@code ~1.2.3}, {@code 1.x},
 * comparateurs {@code >= > <= < =} ; plusieurs contraintes séparées par des espaces sont combinées (ET).
 */
public final class VersionRange {

    private final String text;
    private final List<Predicate<SemVer>> constraints;

    private VersionRange(String text, List<Predicate<SemVer>> constraints) {
        this.text = text;
        this.constraints = constraints;
    }

    public static VersionRange parse(String text) {
        String t = text == null || text.isBlank() ? "*" : text.trim();
        List<Predicate<SemVer>> list = new ArrayList<>();
        for (String part : t.split("\\s+")) {
            list.add(constraint(part));
        }
        return new VersionRange(t, List.copyOf(list));
    }

    public boolean matches(SemVer version) {
        for (Predicate<SemVer> c : constraints) {
            if (!c.test(version)) {
                return false;
            }
        }
        return true;
    }

    private static Predicate<SemVer> constraint(String part) {
        if (part.equals("*") || part.equalsIgnoreCase("x")) {
            return v -> true;
        }
        for (String op : new String[] {">=", "<=", ">", "<", "="}) {
            if (part.startsWith(op)) {
                SemVer bound = SemVer.parse(part.substring(op.length()));
                return switch (op) {
                    case ">=" -> v -> v.compareTo(bound) >= 0;
                    case "<=" -> v -> v.compareTo(bound) <= 0;
                    case ">" -> v -> v.compareTo(bound) > 0;
                    case "<" -> v -> v.compareTo(bound) < 0;
                    default -> v -> v.compareTo(bound) == 0;
                };
            }
        }
        if (part.startsWith("^")) {
            SemVer low = SemVer.parse(part.substring(1));
            SemVer high = low.major() > 0 ? new SemVer(low.major() + 1, 0, 0, null)
                    : low.minor() > 0 ? new SemVer(0, low.minor() + 1, 0, null) : new SemVer(0, 0, low.patch() + 1, null);
            return between(low, high);
        }
        if (part.startsWith("~")) {
            SemVer low = SemVer.parse(part.substring(1));
            return between(low, new SemVer(low.major(), low.minor() + 1, 0, null));
        }
        String[] parts = part.split("\\.");
        if (parts.length < 3 || part.contains("x") || part.contains("*")) {
            // 1, 1.x, 1.2, 1.2.x : préfixe.
            int major = Integer.parseInt(parts[0]);
            boolean minorSet = parts.length > 1 && parts[1].matches("\\d+");
            SemVer low = new SemVer(major, minorSet ? Integer.parseInt(parts[1]) : 0, 0, null);
            return between(low, minorSet ? new SemVer(major, low.minor() + 1, 0, null) : new SemVer(major + 1, 0, 0, null));
        }
        SemVer exact = SemVer.parse(part);
        return v -> v.compareTo(exact) == 0;
    }

    private static Predicate<SemVer> between(SemVer low, SemVer high) {
        return v -> v.compareTo(low) >= 0 && v.compareTo(high) < 0;
    }

    @Override
    public String toString() {
        return text;
    }
}
