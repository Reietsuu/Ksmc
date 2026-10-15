package io.ksmc.api.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Comparaison de versions numériques ({@code 1.8.8}, {@code 1.21.11}, {@code 26.1.2}) et évaluation des plages
 * des règles Java ({@code 1.8-1.16.5}, {@code 1.20.5+}, {@code 26+}). Les suffixes ({@code -rc1}) sont ignorés.
 */
public final class MinecraftVersions {
    public static final Comparator<String> COMPARATOR = MinecraftVersions::compare;

    private MinecraftVersions() {
    }

    public static int compare(String a, String b) {
        List<Integer> x = parts(a);
        List<Integer> y = parts(b);
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            int c = Integer.compare(i < x.size() ? x.get(i) : 0, i < y.size() ? y.get(i) : 0);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static List<Integer> parts(String v) {
        String core = v.trim().split("[-+@ ]", 2)[0];
        List<Integer> out = new ArrayList<>();
        for (String p : core.split("\\.")) {
            try {
                out.add(Integer.parseInt(p));
            } catch (NumberFormatException e) {
                break;
            }
        }
        return out;
    }

    /** Vrai si {@code version} est dans la plage ({@code a-b} inclusive, {@code a+}, ou version exacte). */
    public static boolean inRange(String version, String range) {
        String r = range.trim();
        if (r.endsWith("+")) {
            return compare(version, r.substring(0, r.length() - 1)) >= 0;
        }
        int dash = r.indexOf('-');
        if (dash > 0) {
            return compare(version, r.substring(0, dash)) >= 0 && compare(version, r.substring(dash + 1)) <= 0;
        }
        return compare(version, r) == 0;
    }

    /** Version Java selon les règles (première plage qui correspond), ou {@code fallback}. */
    public static int javaFor(String version, List<SoftwareDefinitionSpec.JavaVersionRule> rules, int fallback) {
        for (SoftwareDefinitionSpec.JavaVersionRule rule : rules) {
            if (inRange(version, rule.minecraft)) {
                return rule.java;
            }
        }
        return fallback;
    }
}
