package io.ksmc.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Accès au cloud depuis un plugin. Les informations sur les services et les tasks viennent d'un cache local tenu à
 * jour en continu (appels immédiats) ; les autres opérations sont asynchrones.
 */
public interface KsmcPluginApi {

    /** Service sur lequel tourne ce plugin. */
    CloudServiceInfo currentService();

    /** Tous les services connus du cloud (namespace courant). */
    List<CloudServiceInfo> services();

    /** Services correspondant au filtre (ex. {@code ServiceQuery.task("bedwars").state("LOBBY")}). */
    List<CloudServiceInfo> services(ServiceQuery query);

    /** Service par nom, ou {@code null}. */
    CloudServiceInfo service(String name);

    /** Tasks déduites des services en cours (nom, environnement, nombre de services et de joueurs). */
    List<TaskInfo> tasks();

    /** Joueurs connectés au réseau (registre des proxies). */
    CompletableFuture<List<PlayerInfo>> players();

    /** Joueur par nom (insensible à la casse) ou UUID ; complété avec {@code null} s'il n'est pas connecté. */
    CompletableFuture<PlayerInfo> player(String nameOrUuid);

    Channels channels();

    Events events();

    /** Stockage clé-valeur partagé (bucket créé par le controller, ex. {@code shared}). */
    KeyValue keyValue(String bucket);

    /** État, motd et propriétés du service courant, visibles dans {@code CloudService.status}. */
    ServiceState state();

    /**
     * Vrai quand la messagerie du cloud est connectée. Avant, les abonnements sont mis en attente et appliqués à la
     * connexion ; les envois et requêtes échouent.
     */
    boolean isConnected();

    /** Exécute une tâche sur le thread principal du serveur. */
    Executor mainThread();
}
