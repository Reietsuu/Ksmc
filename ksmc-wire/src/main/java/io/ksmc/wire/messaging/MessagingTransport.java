package io.ksmc.wire.messaging;

/**
 * Point d'accès à la messagerie Ksmc. Implémentation par défaut : NATS ({@code ksmc-messaging-nats}) ;
 * un module peut en fournir une autre.
 */
public interface MessagingTransport extends AutoCloseable {

    Identity identity();

    ChannelApi channels();

    EventApi events();

    /** Bucket clé-valeur du namespace (créé par le controller s'il n'existe pas). */
    KeyValueApi keyValue(String bucket);

    boolean isConnected();

    @Override
    void close();
}
