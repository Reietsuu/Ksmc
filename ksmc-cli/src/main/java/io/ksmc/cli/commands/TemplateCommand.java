package io.ksmc.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import io.ksmc.cli.Api;
import io.ksmc.cli.BaseCommand;
import io.ksmc.cli.CliException;
import io.ksmc.cli.Output;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "template", description = {"Templates (ServiceTemplate) et fichiers des templates storage.",
        "CHEMIN : chemin du template storage, ex. lobby/default."}, subcommands = {ResourceGroup.ListCmd.class,
        ResourceGroup.GetCmd.class, ResourceGroup.ApplyCmd.class, ResourceGroup.EditCmd.class,
        ResourceGroup.DeleteCmd.class, TemplateCommand.Ls.class, TemplateCommand.Cat.class, TemplateCommand.Put.class,
        TemplateCommand.Rm.class, TemplateCommand.Push.class, TemplateCommand.Pull.class})
public final class TemplateCommand extends ResourceGroup {

    @Override
    protected String path() {
        return "templates";
    }

    @Override
    protected Output.Columns columns() {
        return new Output.Columns().col("NOM", "/metadata/name").col("SOURCE", n -> {
            JsonNode s = n.path("spec");
            for (String type : List.of("storage", "git", "http", "configMap", "image")) {
                if (s.has(type)) {
                    JsonNode v = s.get(type);
                    String detail = v.path("path").asText(v.path("url").asText(v.path("name").asText(v.path("image").asText(""))));
                    return type + (detail.isEmpty() ? "" : " " + detail);
                }
            }
            return "";
        }).col("ÂGE", n -> Output.age(n.at("/metadata/creationTimestamp")));
    }

    static String files(String template) {
        return "/api/v1/templates/" + Api.encodePath(template) + "/files";
    }

    @Command(name = "ls", description = "Lister les fichiers d'un template storage")
    public static final class Ls extends BaseCommand {
        @Parameters(paramLabel = "CHEMIN")
        String template;
        @Mixin
        Output output;

        @Override
        protected void run() {
            output.list(out(), items(api().get(files(template)).path("files")), new Output.Columns()
                    .name(n -> n.path("path").asText()).col("FICHIER", "/path").col("TAILLE", "/size")
                    .col("MODIFIÉ", "/modified"));
        }
    }

    @Command(name = "cat", description = "Afficher un fichier d'un template")
    public static final class Cat extends BaseCommand {
        @Parameters(index = "0", paramLabel = "CHEMIN")
        String template;
        @Parameters(index = "1", paramLabel = "FICHIER")
        String file;

        @Override
        protected void run() throws IOException {
            HttpResponse<InputStream> r = api().send("GET", files(template) + "/" + Api.encodePath(file), null, Map.of());
            try (InputStream in = r.body()) {
                byte[] bytes = in.readAllBytes();
                if (r.statusCode() >= 400) {
                    throw io.ksmc.cli.ApiErrors.of(r.statusCode(), bytes);
                }
                out().print(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                out().flush();
            }
        }
    }

    @Command(name = "put", description = "Écrire un fichier d'un template (depuis un fichier local ou l'entrée standard)")
    public static final class Put extends BaseCommand {
        @Parameters(index = "0", paramLabel = "CHEMIN")
        String template;
        @Parameters(index = "1", paramLabel = "FICHIER", description = "Nom dans le template")
        String file;
        @Parameters(index = "2", paramLabel = "LOCAL", description = "Fichier local (défaut : entrée standard)", arity = "0..1")
        Path local;

        @Override
        protected void run() throws IOException {
            byte[] content = local == null ? System.in.readAllBytes() : Files.readAllBytes(local);
            api().call("PUT", files(template) + "/" + Api.encodePath(file), content,
                    Map.of("Content-Type", "application/octet-stream"));
            out().println(template + "/" + file + " écrit (" + content.length + " octets)");
        }
    }

    @Command(name = "rm", description = "Supprimer un fichier ou un dossier d'un template")
    public static final class Rm extends BaseCommand {
        @Parameters(index = "0", paramLabel = "CHEMIN")
        String template;
        @Parameters(index = "1", paramLabel = "FICHIER")
        String file;

        @Override
        protected void run() {
            api().delete(files(template) + "/" + Api.encodePath(file));
            out().println(template + "/" + file + " supprimé");
        }
    }

    @Command(name = "push", description = "Envoyer un dossier (ou une archive .zip / .tar.gz) dans un template")
    public static final class Push extends BaseCommand {
        @Parameters(index = "0", paramLabel = "CHEMIN")
        String template;
        @Parameters(index = "1", paramLabel = "SOURCE")
        Path source;
        @Option(names = "--merge", description = "Fusionner au lieu de remplacer le contenu")
        boolean merge;

        @Override
        protected void run() throws IOException {
            byte[] archive = Files.isDirectory(source) ? zip(source) : Files.readAllBytes(source);
            JsonNode r = api().call("PUT", "/api/v1/templates/" + Api.encodePath(template) + "/archive"
                    + (merge ? "?mode=merge" : ""), archive, Map.of("Content-Type", "application/zip"));
            out().println(template + " : " + r.path("files").asInt() + " fichier(s) envoyé(s)");
        }

        static byte[] zip(Path dir) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bos); var walk = Files.walk(dir)) {
                for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                    zip.putNextEntry(new ZipEntry(dir.relativize(f).toString().replace('\\', '/')));
                    Files.copy(f, zip);
                    zip.closeEntry();
                }
            }
            return bos.toByteArray();
        }
    }

    @Command(name = "pull", description = "Télécharger un template dans un dossier (ou un .zip)")
    public static final class Pull extends BaseCommand {
        @Parameters(index = "0", paramLabel = "CHEMIN")
        String template;
        @Parameters(index = "1", paramLabel = "DESTINATION")
        Path dest;

        @Override
        protected void run() throws IOException {
            HttpResponse<InputStream> r = api().send("GET", "/api/v1/templates/" + Api.encodePath(template) + "/archive",
                    null, Map.of());
            try (InputStream in = r.body()) {
                if (r.statusCode() >= 400) {
                    throw io.ksmc.cli.ApiErrors.of(r.statusCode(), in.readAllBytes());
                }
                if (dest.toString().endsWith(".zip")) {
                    Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    out().println(template + " → " + dest);
                    return;
                }
                int count = 0;
                Path root = Files.createDirectories(dest).toAbsolutePath().normalize();
                try (ZipInputStream zip = new ZipInputStream(in)) {
                    for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                        Path target = root.resolve(e.getName()).normalize();
                        if (!target.startsWith(root)) {
                            throw CliException.api("entrée d'archive hors du dossier : " + e.getName());
                        }
                        if (!e.isDirectory()) {
                            Files.createDirectories(target.getParent());
                            Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            count++;
                        }
                    }
                }
                out().println(template + " → " + dest + " (" + count + " fichier(s))");
            }
        }
    }
}
