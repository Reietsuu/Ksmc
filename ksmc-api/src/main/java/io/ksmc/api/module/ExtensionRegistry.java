package io.ksmc.api.module;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Registre côté controller : implémentations de SPI fournies par les modules (et par le controller lui-même),
 * regroupées par propriétaire pour être retirées ensemble.
 */
public final class ExtensionRegistry {

    private final Map<Class<?>, List<Entry<?>>> extensions = new ConcurrentHashMap<>();
    private final Map<String, Scope> scopes = new ConcurrentHashMap<>();
    private final Consumer<Throwable> errors;

    private record Entry<T>(String owner, T value) {
    }

    /** @param errors reçoit les erreurs de fermeture des ressources (journalisation) */
    public ExtensionRegistry(Consumer<Throwable> errors) {
        this.errors = errors;
    }

    public ExtensionRegistry() {
        this(e -> { });
    }

    /** Registre d'un propriétaire (module) ; le même objet est renvoyé tant que le scope n'est pas fermé. */
    public Registry scope(String owner) {
        return scopes.computeIfAbsent(owner, Scope::new);
    }

    /** Retire tout ce que le propriétaire a enregistré et ferme ses ressources (ordre inverse). */
    public void closeScope(String owner) {
        Scope s = scopes.remove(owner);
        if (s != null) {
            s.closeAll();
        }
    }

    /** Implémentations actuelles d'une SPI, dans l'ordre d'enregistrement. */
    @SuppressWarnings("unchecked")
    public <T> List<T> all(Class<T> spi) {
        List<T> out = new ArrayList<>();
        for (Entry<?> e : extensions.getOrDefault(spi, List.of())) {
            out.add((T) e.value());
        }
        return out;
    }

    /** Nombre d'enregistrements d'un propriétaire (tests, diagnostic). */
    public int count(String owner) {
        Scope s = scopes.get(owner);
        return s == null ? 0 : s.registrations.size();
    }

    private final class Scope implements Registry {
        private final String owner;
        private final Deque<Registration> registrations = new ConcurrentLinkedDeque<>();

        Scope(String owner) {
            this.owner = owner;
        }

        @Override
        public <T> Registration register(Class<T> spi, T implementation) {
            if (!spi.isInstance(implementation)) {
                throw new IllegalArgumentException(implementation + " n'implémente pas " + spi.getName());
            }
            Entry<T> entry = new Entry<>(owner, implementation);
            extensions.computeIfAbsent(spi, k -> new CopyOnWriteArrayList<>()).add(entry);
            return track(new Registration() {
                @Override
                public void close() {
                    List<Entry<?>> list = extensions.get(spi);
                    if (list != null) {
                        list.remove(entry);
                    }
                }
            });
        }

        @Override
        public Registration onDisable(AutoCloseable resource) {
            return track(new Registration() {
                @Override
                public void close() {
                    try {
                        resource.close();
                    } catch (Exception e) {
                        errors.accept(e);
                    }
                }
            });
        }

        private Registration track(Registration inner) {
            Registration[] self = new Registration[1];
            self[0] = new Registration() {
                private volatile boolean closed;

                @Override
                public void close() {
                    if (!closed) {
                        closed = true;
                        registrations.remove(self[0]);
                        inner.close();
                    }
                }
            };
            registrations.push(self[0]);
            return self[0];
        }

        void closeAll() {
            Registration r;
            while ((r = registrations.peek()) != null) {
                try {
                    r.close();
                } catch (RuntimeException e) {
                    errors.accept(e);
                    registrations.remove(r);
                }
            }
        }
    }
}
