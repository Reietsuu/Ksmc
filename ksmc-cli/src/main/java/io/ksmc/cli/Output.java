package io.ksmc.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import picocli.CommandLine.Option;

/** Option {@code -o table|json|yaml|name} et mise en forme des résultats. */
public final class Output {

    public enum Format { table, json, yaml, name }

    @Option(names = {"-o", "--output"}, description = "Format : table (défaut), json, yaml, name", defaultValue = "table")
    Format format = Format.table;

    /** Colonnes d'un tableau : en-tête → valeur extraite. */
    public static final class Columns {
        final Map<String, Function<JsonNode, String>> columns = new LinkedHashMap<>();
        Function<JsonNode, String> name = n -> n.at("/metadata/name").asText(n.path("name").asText());

        public Columns col(String header, String pointer) {
            columns.put(header, n -> text(n.at(pointer)));
            return this;
        }

        public Columns col(String header, Function<JsonNode, String> value) {
            columns.put(header, value);
            return this;
        }

        public Columns name(Function<JsonNode, String> extractor) {
            this.name = extractor;
            return this;
        }
    }

    public static String textOf(JsonNode n) {
        return text(n);
    }

    public static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return "";
        }
        if (n.isArray()) {
            List<String> parts = new ArrayList<>();
            n.forEach(x -> parts.add(text(x)));
            return String.join(",", parts);
        }
        return n.isValueNode() ? n.asText() : n.toString();
    }

    /** Âge lisible d'un horodatage ISO-8601 ({@code 5m}, {@code 3h}, {@code 2d}). */
    public static String age(JsonNode timestamp) {
        if (timestamp == null || !timestamp.isTextual()) {
            return "";
        }
        try {
            Duration d = Duration.between(Instant.parse(timestamp.asText()), Instant.now());
            long s = Math.max(0, d.toSeconds());
            return s < 120 ? s + "s" : s < 7200 ? s / 60 + "m" : s < 172800 ? s / 3600 + "h" : s / 86400 + "d";
        } catch (RuntimeException e) {
            return "";
        }
    }

    public void format(Format f) {
        this.format = f;
    }

    public Format format() {
        return format;
    }

    public void list(PrintWriter out, List<JsonNode> items, Columns columns) {
        switch (format) {
            case json -> write(out, Api.JSON.valueToTree(items), false);
            case yaml -> write(out, Api.JSON.valueToTree(items), true);
            case name -> items.forEach(i -> out.println(columns.name.apply(i)));
            case table -> table(out, items, columns);
        }
        out.flush();
    }

    public void one(PrintWriter out, JsonNode item, Columns columns) {
        switch (format) {
            case json -> write(out, item, false);
            case name -> out.println(columns.name.apply(item));
            case table -> table(out, List.of(item), columns);
            // Une ressource seule se lit mieux en YAML qu'en tableau quand on en demande le détail.
            case yaml -> write(out, item, true);
        }
        out.flush();
    }

    static void write(PrintWriter out, JsonNode node, boolean yaml) {
        try {
            if (yaml) {
                out.print(Config.YAML.writeValueAsString(node).replaceFirst("^---\n", ""));
            } else {
                out.println(Api.JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(node));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void table(PrintWriter out, List<JsonNode> items, Columns columns) {
        List<String> headers = new ArrayList<>(columns.columns.keySet());
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode i : items) {
            List<String> row = new ArrayList<>();
            columns.columns.values().forEach(f -> row.add(f.apply(i)));
            rows.add(row);
        }
        int[] widths = new int[headers.size()];
        for (int c = 0; c < headers.size(); c++) {
            widths[c] = headers.get(c).length();
            for (List<String> r : rows) {
                widths[c] = Math.max(widths[c], r.get(c).length());
            }
        }
        out.println(line(headers, widths));
        rows.forEach(r -> out.println(line(r, widths)));
    }

    private static String line(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cells.size(); c++) {
            sb.append(c == cells.size() - 1 ? cells.get(c) : String.format("%-" + (widths[c] + 3) + "s", cells.get(c)));
        }
        return sb.toString().stripTrailing();
    }
}
