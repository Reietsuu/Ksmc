package io.ksmc.controller.template;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.ksmc.api.model.ServiceTemplate;
import io.ksmc.api.spi.TemplateContext;
import io.ksmc.api.spi.TemplateSourceProvider;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Source {@code configMap} : chaque clé devient un fichier. Les clés ne pouvant pas contenir {@code /},
 * {@code __} sert de séparateur de dossier ({@code plugins__config.yml} → {@code plugins/config.yml}).
 */
public final class ConfigMapTemplateSource implements TemplateSourceProvider {

    @Override
    public String type() {
        return "configMap";
    }

    @Override
    public boolean supports(ServiceTemplate template) {
        return template.getSpec().configMap != null;
    }

    private static ConfigMap load(ServiceTemplate template, TemplateContext ctx) throws FileNotFoundException {
        String name = template.getSpec().configMap.name;
        ConfigMap cm = ctx.kubernetes().configMaps().inNamespace(ctx.namespace()).withName(name).get();
        if (cm == null) {
            throw new FileNotFoundException("ConfigMap not found: " + name);
        }
        return cm;
    }

    @Override
    public String digest(ServiceTemplate template, TemplateContext ctx) throws IOException {
        ConfigMap cm = load(template, ctx);
        return Digests.sha256(cm.getMetadata().getUid() + "/" + cm.getMetadata().getResourceVersion());
    }

    @Override
    public void writeArchive(ServiceTemplate template, TemplateContext ctx, OutputStream out) throws IOException {
        ConfigMap cm = load(template, ctx);
        Map<String, byte[]> files = new TreeMap<>();
        if (cm.getData() != null) {
            cm.getData().forEach((k, v) -> files.put(path(k), v.getBytes(StandardCharsets.UTF_8)));
        }
        if (cm.getBinaryData() != null) {
            cm.getBinaryData().forEach((k, v) -> files.put(path(k), Base64.getDecoder().decode(v)));
        }
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(f.getKey()));
                zip.write(f.getValue());
                zip.closeEntry();
            }
        }
    }

    static String path(String key) {
        return key.replace("__", "/");
    }
}
