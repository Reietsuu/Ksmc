package io.ksmc.plugin;

import java.util.Map;

/** État publié par le service courant (reporté dans {@code CloudService.status} et visible des autres services). */
public interface ServiceState {

    /** État personnalisé (ex. {@code LOBBY}, {@code INGAME}), {@code null} pour l'effacer. */
    void setState(String state);

    String state();

    /** Motd annoncé au cloud, {@code null} pour reprendre celui du serveur. */
    void setMotd(String motd);

    /** Propriété libre ({@code null} pour la retirer), ex. {@code setProperty("map", "Desert")}. */
    void setProperty(String key, String value);

    Map<String, String> properties();
}
