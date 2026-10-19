package io.ksmc.api.module;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Version sémantique {@code MAJEUR.MINEUR.CORRECTIF[-préversion][+build]} ; le build est ignoré à la comparaison. */
public record SemVer(int major, int minor, int patch, String preRelease) implements Comparable<SemVer> {

    private static final Pattern FORMAT =
            Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?");

    /** Accepte aussi {@code 1} et {@code 1.2} (composantes manquantes = 0). */
    public static SemVer parse(String text) {
        Matcher m = FORMAT.matcher(text == null ? "" : text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("version invalide : " + text);
        }
        return new SemVer(Integer.parseInt(m.group(1)), m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3)), m.group(4));
    }

    @Override
    public int compareTo(SemVer o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        if (c == 0) {
            c = Integer.compare(patch, o.patch);
        }
        if (c != 0) {
            return c;
        }
        if (preRelease == null || o.preRelease == null) {
            // Une préversion précède la version finale.
            return preRelease == null ? (o.preRelease == null ? 0 : 1) : -1;
        }
        return comparePreRelease(preRelease, o.preRelease);
    }

    private static int comparePreRelease(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
            boolean na = pa[i].matches("\\d+");
            boolean nb = pb[i].matches("\\d+");
            int c = na && nb ? Long.compare(Long.parseLong(pa[i]), Long.parseLong(pb[i]))
                    : na ? -1 : nb ? 1 : pa[i].compareTo(pb[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(pa.length, pb.length);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease == null ? "" : "-" + preRelease);
    }
}
