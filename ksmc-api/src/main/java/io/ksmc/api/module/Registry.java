package io.ksmc.api.module;

/**
 * Enregistrements d'un module. Tout ce qui est enregistré ici est retiré automatiquement quand le module est
 * désactivé, rechargé ou en échec.
 */
public interface Registry {

    /** Ajoute une implémentation de SPI (ex. {@code register(SoftwareResolver.class, new S3Resolver())}). */
    <T> Registration register(Class<T> spi, T implementation);

    /** Ressource fermée à la désactivation (abonnement, thread, client...). */
    Registration onDisable(AutoCloseable resource);

    /** Retrait anticipé d'un enregistrement. */
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
