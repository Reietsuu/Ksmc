package io.ksmc.wrapper;

import java.util.ArrayList;
import java.util.List;

/** Édition minimale de TOML par lignes : clés de premier niveau et sections. */
final class TomlLines {
    private final List<String> lines = new ArrayList<String>();

    TomlLines(String content) {
        if (content != null && !content.isEmpty()) {
            for (String l : content.split("\r?\n")) {
                lines.add(l);
            }
        }
    }

    private int firstSection() {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("[")) {
                return i;
            }
        }
        return lines.size();
    }

    /** Fixe une clé de premier niveau (valeur TOML déjà formatée). */
    TomlLines setTop(String key, String value) {
        int end = firstSection();
        for (int i = 0; i < end; i++) {
            String t = lines.get(i).trim();
            if (t.startsWith(key + " ") || t.startsWith(key + "=")) {
                lines.set(i, key + " = " + value);
                return this;
            }
        }
        lines.add(end, key + " = " + value);
        return this;
    }

    /** Ajoute la section avec ces lignes si elle n'existe pas. */
    /** Écrit {@code key = value} dans la section {@code [section]} (créée au besoin). */
    TomlLines set(String section, String key, String value) {
        ensureSection(section);
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("[" + section + "]")) {
                start = i;
                break;
            }
        }
        int i = start + 1;
        for (; i < lines.size() && !lines.get(i).trim().startsWith("["); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith(key + " ") || t.startsWith(key + "=")) {
                lines.set(i, key + " = " + value);
                return this;
            }
        }
        lines.add(start + 1, key + " = " + value);
        return this;
    }

    TomlLines ensureSection(String name, String... body) {
        for (String l : lines) {
            if (l.trim().equals("[" + name + "]")) {
                return this;
            }
        }
        lines.add("");
        lines.add("[" + name + "]");
        for (String b : body) {
            lines.add(b);
        }
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
