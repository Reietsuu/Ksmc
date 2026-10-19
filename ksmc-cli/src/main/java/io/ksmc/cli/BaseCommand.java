package io.ksmc.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Base des commandes : accès à l'API du contexte courant et aux sorties. */
public abstract class BaseCommand implements Callable<Integer> {

    @Spec
    protected CommandSpec spec;

    protected Main main() {
        return (Main) spec.root().userObject();
    }

    protected Api api() {
        return main().api();
    }

    protected PrintWriter out() {
        return spec.commandLine().getOut();
    }

    protected PrintWriter err() {
        return spec.commandLine().getErr();
    }

    @Override
    public final Integer call() throws Exception {
        run();
        return 0;
    }

    protected abstract void run() throws Exception;

    /** Éléments d'une réponse {@code {"items": [...]}} ou d'un tableau JSON. */
    protected static List<JsonNode> items(JsonNode n) {
        JsonNode array = n.isArray() ? n : n.path("items");
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }
}
