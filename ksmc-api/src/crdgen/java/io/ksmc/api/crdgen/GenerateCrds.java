package io.ksmc.api.crdgen;

import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.crdv2.generator.CRDGenerator;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceColumnDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.ksmc.api.model.Ksmc;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Génère les CRDs v1 de Ksmc dans le dossier passé en argument. */
public final class GenerateCrds {
    public static void main(String[] args) {
        File out = new File(args[0]);
        if (!out.isDirectory() && !out.mkdirs()) {
            throw new IllegalStateException("cannot create " + out);
        }
        List<Class<? extends io.fabric8.kubernetes.api.model.HasMetadata>> types = new ArrayList<>(Ksmc.RESOURCE_TYPES);
        int count = new CRDGenerator()
                .inOutputDir(out)
                .forCRDVersions("v1")
                .withParallelGenerationEnabled(false)
                .withPostProcessor(new io.fabric8.crdv2.generator.CRDPostProcessor() {
                    @Override
                    public io.fabric8.kubernetes.api.model.HasMetadata process(
                            io.fabric8.kubernetes.api.model.HasMetadata crd, String version) {
                        orderColumns((CustomResourceDefinition) crd);
                        return crd;
                    }
                })
                .customResourceClasses(types)
                .generate();
        if (count != types.size()) {
            throw new IllegalStateException("expected " + types.size() + " CRDs, generated " + count);
        }
    }

    /** Le générateur trie les colonnes par nom : on rétablit l'ordre de déclaration des annotations. */
    private static void orderColumns(CustomResourceDefinition crd) {
        Class<?> type = Ksmc.RESOURCE_TYPES.stream()
                .filter(c -> c.getSimpleName().equals(crd.getSpec().getNames().getKind()))
                .findFirst().orElseThrow();
        List<String> order = Arrays.stream(type.getAnnotationsByType(AdditionalPrinterColumn.class))
                .map(AdditionalPrinterColumn::name).toList();
        crd.getSpec().getVersions().forEach(v -> {
            List<CustomResourceColumnDefinition> cols = v.getAdditionalPrinterColumns();
            if (cols != null) {
                cols.sort(Comparator.comparingInt(c -> order.indexOf(c.getName())));
            }
        });
    }

    private GenerateCrds() {
    }
}
