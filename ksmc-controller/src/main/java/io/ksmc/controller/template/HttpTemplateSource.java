package io.ksmc.controller.template;

import io.ksmc.api.model.ServiceTemplate;
import io.ksmc.api.spi.TemplateContext;
import io.ksmc.api.spi.TemplateSourceProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;

/** Source {@code http} : archive ZIP ou tar.gz téléchargée, vérifiée par sha256 si fourni. */
public final class HttpTemplateSource implements TemplateSourceProvider {
    private final HttpClient http;

    public HttpTemplateSource(HttpClient http) {
        this.http = http;
    }

    @Override
    public String type() {
        return "http";
    }

    @Override
    public boolean supports(ServiceTemplate template) {
        return template.getSpec().http != null;
    }

    @Override
    public String digest(ServiceTemplate template, TemplateContext ctx) throws IOException {
        var spec = template.getSpec().http;
        if (spec.sha256 != null) {
            return spec.sha256.toLowerCase();
        }
        HttpResponse<Void> head = send(HttpRequest.newBuilder(URI.create(spec.url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()), HttpResponse.BodyHandlers.discarding());
        Optional<String> tag = head.headers().firstValue("ETag").or(() -> head.headers().firstValue("Last-Modified"));
        // Sans validateur HTTP, on ne peut pas savoir si le contenu a changé : empreinte basée sur l'URL et l'heure.
        return Digests.sha256(spec.url + "|" + tag.orElse(String.valueOf(System.currentTimeMillis() / 600_000)));
    }

    @Override
    public void writeArchive(ServiceTemplate template, TemplateContext ctx, OutputStream out) throws IOException {
        var spec = template.getSpec().http;
        Path tmp = Files.createTempDirectory("ksmc-http-tpl");
        try {
            HttpResponse<InputStream> resp = send(HttpRequest.newBuilder(URI.create(spec.url)).GET(),
                    HttpResponse.BodyHandlers.ofInputStream());
            MessageDigest md = Digests.sha256();
            Path archive = tmp.resolve("archive");
            try (InputStream body = new DigestInputStream(resp.body(), md)) {
                Files.copy(body, archive);
            }
            String actual = Digests.hex(md.digest());
            if (spec.sha256 != null && !spec.sha256.equalsIgnoreCase(actual)) {
                throw new IOException("sha256 mismatch for " + spec.url + ": expected " + spec.sha256 + ", got " + actual);
            }
            Path files = Files.createDirectory(tmp.resolve("files"));
            try (InputStream in = Files.newInputStream(archive)) {
                ZipArchives.extract(in, files);
            }
            ZipArchives.fromDirectory(files, out);
        } finally {
            ZipArchives.deleteRecursively(tmp);
        }
    }

    private <T> HttpResponse<T> send(HttpRequest.Builder req, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            HttpResponse<T> resp = http.send(req.timeout(Duration.ofMinutes(2)).header("User-Agent", "ksmc").build(), handler);
            if (resp.statusCode() >= 400) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + resp.uri());
            }
            return resp;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
