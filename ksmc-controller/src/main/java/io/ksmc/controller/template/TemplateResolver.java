package io.ksmc.controller.template;

import io.ksmc.api.model.ServiceTemplate;
import io.ksmc.api.spi.ArtifactStorage;
import io.ksmc.api.spi.TemplateContext;
import io.ksmc.api.spi.TemplateSourceProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Résout un {@link ServiceTemplate} en archive ZIP mise en cache dans le stockage
 * ({@code cache/templates/<ns>/<nom>/<empreinte>.zip}). Les fournisseurs sont extensibles par les modules.
 */
public final class TemplateResolver {
    public static final String CACHE_PREFIX = "cache/templates/";

    private final List<TemplateSourceProvider> providers = new CopyOnWriteArrayList<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public TemplateResolver(List<TemplateSourceProvider> builtins) {
        providers.addAll(builtins);
    }

    public void register(TemplateSourceProvider provider) {
        providers.addFirst(provider);
    }

    public void unregister(TemplateSourceProvider provider) {
        providers.remove(provider);
    }

    public TemplateSourceProvider providerFor(ServiceTemplate template) {
        return providers.stream().filter(p -> p.supports(template)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no template source provider for " + template.getMetadata().getName()));
    }

    /** Résultat : clé de l'archive dans le stockage et empreinte du contenu. */
    public record Resolved(String key, String digest) {
    }

    public Resolved resolve(ServiceTemplate template, TemplateContext ctx) throws IOException {
        TemplateSourceProvider provider = providerFor(template);
        String digest = provider.digest(template, ctx);
        String dir = CACHE_PREFIX + ctx.namespace() + "/" + template.getMetadata().getName() + "/";
        String key = dir + digest + ".zip";
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        try {
            synchronized (lock) {
                ArtifactStorage storage = ctx.storage();
                if (!storage.exists(key)) {
                    storage.write(key, archive(provider, template, ctx));
                    for (String old : storage.list(dir)) {
                        if (!old.equals(key)) {
                            storage.delete(old);
                        }
                    }
                }
            }
        } finally {
            locks.remove(key, lock);
        }
        return new Resolved(key, digest);
    }

    /** Produit l'archive en streaming (pas de copie complète en mémoire). */
    private static InputStream archive(TemplateSourceProvider provider, ServiceTemplate template, TemplateContext ctx)
            throws IOException {
        PipedInputStream in = new PipedInputStream(64 * 1024);
        PipedOutputStream out = new PipedOutputStream(in);
        AtomicReference<IOException> failure = new AtomicReference<>();
        Thread.ofVirtual().name("template-archive").start(() -> {
            try {
                provider.writeArchive(template, ctx, out);
            } catch (IOException | RuntimeException e) {
                // L'échec doit être visible avant que le lecteur ne voie la fin du flux.
                failure.set(e instanceof IOException io ? io : new IOException(e));
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // lecteur déjà fermé
                }
            }
        });
        return new InputStream() {
            @Override
            public int read() throws IOException {
                int b = in.read();
                check(b);
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = in.read(b, off, len);
                check(n);
                return n;
            }

            private void check(int n) throws IOException {
                if (n < 0 && failure.get() != null) {
                    throw failure.get();
                }
            }

            @Override
            public void close() throws IOException {
                in.close();
            }
        };
    }
}
