package io.ksmc.controller.template;

import io.fabric8.kubernetes.api.model.Secret;
import io.ksmc.api.model.ServiceTemplate;
import io.ksmc.api.model.ServiceTemplateSpec;
import io.ksmc.api.spi.TemplateContext;
import io.ksmc.api.spi.TemplateSourceProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collection;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/** Source {@code git} : dépôt cloné au commit courant de la ref (HEAD par défaut), sous-dossier optionnel. */
public final class GitTemplateSource implements TemplateSourceProvider {

    @Override
    public String type() {
        return "git";
    }

    @Override
    public boolean supports(ServiceTemplate template) {
        return template.getSpec().git != null;
    }

    @Override
    public String digest(ServiceTemplate template, TemplateContext ctx) throws IOException {
        ServiceTemplateSpec.Git git = template.getSpec().git;
        return Digests.sha256(git.url + "|" + git.path + "|" + resolveCommit(git, ctx));
    }

    private String resolveCommit(ServiceTemplateSpec.Git git, TemplateContext ctx) throws IOException {
        if (git.ref != null && git.ref.matches("[0-9a-f]{40}")) {
            return git.ref;
        }
        try {
            LsRemoteCommand ls = Git.lsRemoteRepository().setRemote(git.url).setHeads(true).setTags(true);
            auth(ls, git, ctx);
            Collection<Ref> refs = ls.call();
            String wanted = git.ref == null ? null : git.ref;
            for (Ref ref : refs) {
                String name = ref.getName();
                if (wanted == null ? name.equals("HEAD") : name.equals(wanted) || name.equals("refs/heads/" + wanted)
                        || name.equals("refs/tags/" + wanted)) {
                    return (ref.getPeeledObjectId() != null ? ref.getPeeledObjectId() : ref.getObjectId()).name();
                }
            }
            if (wanted == null) {
                // ls-remote ne renvoie pas toujours HEAD (setHeads) : on relance sans filtre.
                Ref head = Git.lsRemoteRepository().setRemote(git.url).callAsMap().get("HEAD");
                if (head != null) {
                    return head.getObjectId().name();
                }
            }
            throw new FileNotFoundException("git ref not found: " + (wanted == null ? "HEAD" : wanted) + " in " + git.url);
        } catch (GitAPIException e) {
            throw new IOException("git ls-remote failed for " + git.url + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void writeArchive(ServiceTemplate template, TemplateContext ctx, OutputStream out) throws IOException {
        ServiceTemplateSpec.Git git = template.getSpec().git;
        String commit = resolveCommit(git, ctx);
        Path tmp = Files.createTempDirectory("ksmc-git-tpl");
        try {
            var clone = Git.cloneRepository().setURI(git.url).setDirectory(tmp.toFile()).setNoCheckout(true);
            auth(clone, git, ctx);
            try (Git repo = clone.call()) {
                repo.checkout().setName(commit).call();
            }
            Path dir = git.path == null ? tmp : tmp.resolve(git.path).normalize();
            if (!dir.startsWith(tmp) || !Files.isDirectory(dir)) {
                throw new FileNotFoundException("path not found in repository: " + git.path);
            }
            ZipArchives.fromDirectory(dir, out);
        } catch (GitAPIException e) {
            throw new IOException("git clone failed for " + git.url + ": " + e.getMessage(), e);
        } finally {
            ZipArchives.deleteRecursively(tmp);
        }
    }

    private static void auth(TransportCommand<?, ?> cmd, ServiceTemplateSpec.Git git, TemplateContext ctx)
            throws FileNotFoundException {
        if (git.secretRef == null) {
            return;
        }
        Secret secret = ctx.kubernetes().secrets().inNamespace(ctx.namespace()).withName(git.secretRef).get();
        if (secret == null || secret.getData() == null) {
            throw new FileNotFoundException("git secret not found: " + git.secretRef);
        }
        cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                decode(secret.getData().getOrDefault("username", "")), decode(secret.getData().getOrDefault("password", ""))));
    }

    private static String decode(String b64) {
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }
}
