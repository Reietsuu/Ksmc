package io.ksmc.wire.bridge;

/** Action sur un joueur, exécutée par le proxy qui l'héberge ({@code ksmc.<ns>.rpc.player.<proxy>}). */
public class PlayerAction {
    public enum Type { SEND, KICK, MESSAGE, TITLE }

    public Type type;
    /** UUID ou nom du joueur. */
    public String player;
    /** SEND : service cible, ou task cible (le service le moins rempli est choisi). */
    public String targetService;
    public String targetTask;
    /** KICK : motif ; MESSAGE : texte ; TITLE : titre. */
    public String text;
    public String subtitle;

    public static PlayerAction of(Type type, String player) {
        PlayerAction a = new PlayerAction();
        a.type = type;
        a.player = player;
        return a;
    }
}
