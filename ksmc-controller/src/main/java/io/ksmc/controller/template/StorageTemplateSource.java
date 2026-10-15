package io.ksmc.controller.template;

import io.ksmc.api.model.ServiceTemplate;
import io.ksmc.api.spi.ArtifactStorage;
import io.ksmc.api.spi.TemplateContext;
import io.ksmc.api.spi.TemplateSourceProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** Source {@code storage} : dossier du Template Store ({@code templates/<path>}). */
public final class StorageTemplateSource implements TemplateSourceProvider {
    /** Préfixe des templates dans le stockage du controller. */
    public static final String PREFIX = "templates/";

    @Override
    public String type() {
        return "storage";
    }

    @Override
    public boolean supports(ServiceTemplate template) {
        return template.getSpec().storage != null;
    }

    static String prefix(ServiceTemplate template) {
        return PREFIX + ArtifactStorage.normalize(template.getSpec().storage.path);
    }

    @Override
    public String digest(ServiceTemplate template, TemplateContext ctx) throws IOException {
        String prefix = prefix(template);
        List<String> keys = ctx.storage().list(prefix);
        if (keys.isEmpty()) {
            throw new FileNotFoundException("template store path is empty: " + template.getSpec().storage.path);
        }
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            sb.append(key).append(':').append(ctx.storage().size(key)).append(':')
                    .append(ctx.storage().lastModified(key)).append('\n');
        }
        return Digests.sha256(sb.toString());
    }

    @Override
    public void writeArchive(ServiceTemplate template, TemplateContext ctx, OutputStream out) throws IOException {
        ZipArchives.fromStorage(ctx.storage(), prefix(template), out);
    }
}
