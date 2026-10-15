package io.ksmc.wire.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tout ce dont le wrapper a besoin pour préparer et lancer un service, calculé par le controller
 * ({@code GET /internal/v1/services/{ns}/{name}/manifest}). Format JSON, champs inconnus ignorés.
 */
public class ServiceManifest {
    /** Version du format. */
    public int v = 1;
    public String namespace;
    public String service;
    public String task;
    /** {@code proxy} ou {@code server}. */
    public String environment;
    /** Famille du logiciel : paper, spigot, velocity, bungeecord, fabric, forge, neoforge, generic. */
    public String family;
    public String softwareVersion;
    public String softwareBuild;
    public boolean staticService;
    /** EULA Minecraft acceptée explicitement par l'opérateur (valeur du chart {@code minecraft.acceptEula}). */
    public boolean acceptEula;

    /** Fichiers du logiciel (jar ou archive construite), appliqués avant les templates. */
    public List<Artifact> artifacts = new ArrayList<Artifact>();
    /** Templates dans l'ordre d'application. */
    public List<Template> templates = new ArrayList<Template>();
    public NetworkConfig network = new NetworkConfig();
    public Launch launch = new Launch();
    /** Sauvegarde vers un template à l'arrêt, ou {@code null}. */
    public Deploy deployOnStop;
    /** Propriétés libres (task + groupes), exposées aux plugins. */
    public Map<String, String> properties = new LinkedHashMap<String, String>();

    public static class Artifact {
        /** URL (souvent l'API interne du controller). */
        public String url;
        public String sha256;
        /** Chemin cible relatif au dossier du service ; si {@code extract}, dossier où extraire le ZIP. */
        public String target;
        public boolean extract;
    }

    public static class Template {
        public String name;
        /** Archive ZIP à télécharger, ou {@code null} si {@link #localPath} est utilisé. */
        public String url;
        /** Dossier déjà présent dans le Pod (source {@code image}, copiée par un initContainer). */
        public String localPath;
        public boolean optional;
    }

    public static class NetworkConfig {
        public int port = 25565;
        /** Forwarding entre proxy et serveurs : {@code modern} (Velocity), {@code legacy} (BungeeCord), {@code none}. */
        public String forwarding = "modern";
        /** Fichier contenant le secret de forwarding moderne (monté depuis un Secret). */
        public String forwardingSecretFile;
        /** Proxies : accepter le PROXY protocol (IP réelle des joueurs derrière un load balancer). */
        public boolean proxyProtocol;
        /** Proxies : nombre maximal de joueurs affiché. */
        public Integer maxPlayers;
        public String motd;
    }

    public static class Launch {
        /** Options JVM complètes (mémoire comprise). */
        public List<String> jvmOptions = new ArrayList<String>();
        /** Arguments après les options JVM (ex. {@code -jar server.jar nogui}). */
        public List<String> args = new ArrayList<String>();
        public String stopCommand = "stop";
        public int stopTimeoutSeconds = 30;
    }

    public static class Deploy {
        public String url;
        public List<String> excludes = new ArrayList<String>();
    }
}
