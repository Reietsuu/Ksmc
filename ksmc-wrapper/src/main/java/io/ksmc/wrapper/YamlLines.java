package io.ksmc.wrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Édition minimale de YAML par lignes (clés imbriquées par indentation), suffisante pour les fichiers de
 * configuration Minecraft. Préserve commentaires et mise en forme ; crée les clés manquantes.
 */
final class YamlLines {
    private final List<String> lines;

    YamlLines(String content) {
        lines = new ArrayList<String>();
        if (content != null && !content.isEmpty()) {
            for (String l : content.split("\r?\n", -1)) {
                lines.add(l);
            }
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
        }
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static boolean blank(String line) {
        String t = line.trim();
        return t.isEmpty() || t.startsWith("#");
    }

    private static boolean isKey(String line, String key) {
        String t = line.trim();
        return t.startsWith(key + ":") || t.startsWith("'" + key + "':") || t.startsWith("\"" + key + "\":");
    }

    /** Fixe {@code a.b.c} à une valeur YAML déjà formatée (ex. {@code true}, {@code 'secret'}). */
    YamlLines set(String path, String value) {
        String[] keys = path.split("\\.");
        int start = 0;
        int end = lines.size();
        int level = 0;
        for (int k = 0; k < keys.length; k++) {
            int found = -1;
            for (int i = start; i < end; i++) {
                String l = lines.get(i);
                if (!blank(l) && indent(l) == level && isKey(l, keys[k])) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                insert(keys, k, level, end, value);
                return this;
            }
            if (k == keys.length - 1) {
                String l = lines.get(found);
                lines.set(found, l.substring(0, l.indexOf(':') + 1) + " " + value);
                return this;
            }
            start = found + 1;
            end = start;
            while (end < lines.size() && (blank(lines.get(end)) || indent(lines.get(end)) > level)) {
                end++;
            }
            int child = level + 2;
            for (int i = start; i < end; i++) {
                if (!blank(lines.get(i))) {
                    child = indent(lines.get(i));
                    break;
                }
            }
            level = child;
        }
        return this;
    }

    private void insert(String[] keys, int from, int level, int at, String value) {
        List<String> added = new ArrayList<String>();
        int indent = level;
        for (int k = from; k < keys.length; k++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < indent; i++) {
                sb.append(' ');
            }
            sb.append(keys[k]).append(':');
            if (k == keys.length - 1) {
                sb.append(' ').append(value);
            }
            added.add(sb.toString());
            indent += 2;
        }
        lines.addAll(at, added);
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
