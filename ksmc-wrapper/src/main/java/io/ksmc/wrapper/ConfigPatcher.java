package io.ksmc.wrapper;

import io.ksmc.wire.manifest.ServiceManifest;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applique la configuration réseau imposée par Ksmc (port, mode en ligne, forwarding proxy, PROXY protocol)
 * aux fichiers du logiciel, après les templates. Les autres réglages des templates sont conservés.
 */
final class ConfigPatcher {

    void apply(ServiceManifest m, File dir) throws IOException {
        String family = m.family == null ? "generic" : m.family;
        String secret = secret(m);
        if ("velocity".equals(family)) {
            velocity(m, dir, secret);
        } else if ("bungeecord".equals(family)) {
            bungee(m, dir);
        } else {
            server(m, dir, family, secret);
        }
    }

    private static String secret(ServiceManifest m) throws IOException {
        String file = m.network.forwardingSecretFile;
        if (file == null || !new File(file).isFile()) {
            return null;
        }
        return IoUtil.readString(new File(file)).trim();
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    // ---- Serveurs de jeu -------------------------------------------------------------------------

    private void server(ServiceManifest m, File dir, String family, String secret) throws IOException {
        Map<String, String> props = new LinkedHashMap<String, String>();
        props.put("server-port", String.valueOf(m.network.port));
        props.put("server-ip", "");
        // Derrière un proxy : l'authentification est faite par le proxy.
        props.put("online-mode", "false");
        if (m.network.motd != null) {
            props.put("motd", m.network.motd);
        }
        patchProperties(new File(dir, "server.properties"), props);
        if (m.acceptEula) {
            IoUtil.writeString(new File(dir, "eula.txt"), "# Accepted via Ksmc (minecraft.acceptEula)\neula=true\n");
        }

        String forwarding = m.network.forwarding == null ? "none" : m.network.forwarding;
        boolean modern = "modern".equals(forwarding) && secret != null;
        boolean legacy = "legacy".equals(forwarding);
        if ("paper".equals(family) || "spigot".equals(family)) {
            boolean paperModern = modern && "paper".equals(family) && compare(m.softwareVersion, "1.13") >= 0;
            if (modern && !paperModern) {
                Log.warn("forwarding moderne indisponible pour " + family + " " + m.softwareVersion + " : mode legacy");
            }
            patchYaml(new File(dir, "spigot.yml"), "settings.bungeecord", String.valueOf(legacy || (modern && !paperModern)));
            if (paperModern) {
                if (compare(m.softwareVersion, "1.19") >= 0) {
                    File global = new File(dir, "config/paper-global.yml");
                    patchYaml(global, "proxies.velocity.enabled", "true");
                    patchYaml(global, "proxies.velocity.online-mode", "true");
                    patchYaml(global, "proxies.velocity.secret", quote(secret));
                } else {
                    File paper = new File(dir, "paper.yml");
                    patchYaml(paper, "settings.velocity-support.enabled", "true");
                    patchYaml(paper, "settings.velocity-support.online-mode", "true");
                    patchYaml(paper, "settings.velocity-support.secret", quote(secret));
                }
            }
        } else if ("fabric".equals(family) && modern) {
            File toml = new File(dir, "config/FabricProxy-Lite.toml");
            String content = toml.isFile() ? IoUtil.readString(toml) : "";
            IoUtil.writeString(toml, new TomlLines(content).setTop("hackOnlineMode", "true")
                    .setTop("secret", "\"" + secret + "\"").toString());
        }
    }

    // ---- Velocity --------------------------------------------------------------------------------

    private void velocity(ServiceManifest m, File dir, String secret) throws IOException {
        File file = new File(dir, "velocity.toml");
        TomlLines toml = new TomlLines(file.isFile() ? IoUtil.readString(file) : "config-version = \"2.7\"\n");
        String mode = "legacy".equals(m.network.forwarding) ? "legacy"
                : "none".equals(m.network.forwarding) ? "none" : "modern";
        toml.setTop("bind", "\"0.0.0.0:" + m.network.port + "\"")
                .setTop("online-mode", String.valueOf(onlineMode(m)))
                .setTop("player-info-forwarding-mode", "\"" + mode + "\"")
                .setTop("forwarding-secret-file", "\"forwarding.secret\"");
        if (m.network.maxPlayers != null) {
            toml.setTop("show-max-players", String.valueOf(m.network.maxPlayers));
        }
        if (m.network.motd != null) {
            toml.setTop("motd", "\"" + m.network.motd.replace("\"", "\\\"") + "\"");
        }
        // Les serveurs sont ajoutés dynamiquement par le bridge Ksmc.
        toml.ensureSection("servers", "try = []").ensureSection("forced-hosts")
                .ensureSection("advanced", "haproxy-protocol = " + m.network.proxyProtocol);
        IoUtil.writeString(file, toml.toString());
        if (secret != null) {
            IoUtil.writeString(new File(dir, "forwarding.secret"), secret);
        }
    }

    // ---- BungeeCord / Waterfall -----------------------------------------------------------------

    private void bungee(ServiceManifest m, File dir) throws IOException {
        File file = new File(dir, "config.yml");
        if (!file.isFile()) {
            IoUtil.writeString(file, "listeners:\n"
                    + "- host: 0.0.0.0:" + m.network.port + "\n"
                    + "  query_port: 25577\n"
                    + "  motd: " + quote(m.network.motd == null ? "Ksmc" : m.network.motd) + "\n"
                    + "  max_players: " + (m.network.maxPlayers == null ? 500 : m.network.maxPlayers) + "\n"
                    + "  priorities: []\n"
                    + "  force_default_server: false\n"
                    + "  proxy_protocol: " + m.network.proxyProtocol + "\n"
                    + "  forced_hosts: {}\n"
                    + "  tab_list: GLOBAL_PING\n"
                    + "  ping_passthrough: false\n"
                    + "ip_forward: true\n"
                    + "online_mode: " + onlineMode(m) + "\n"
                    + "servers: {}\n");
            return;
        }
        StringBuilder out = new StringBuilder();
        for (String line : IoUtil.readString(file).split("\r?\n")) {
            String t = line.trim();
            String indent = line.substring(0, line.indexOf(t.isEmpty() ? line : t));
            if (t.startsWith("ip_forward:")) {
                line = indent + "ip_forward: true";
            } else if (t.startsWith("online_mode:")) {
                line = indent + "online_mode: " + onlineMode(m);
            } else if (t.startsWith("- host:") || t.startsWith("host:")) {
                line = indent + (t.startsWith("-") ? "- " : "") + "host: 0.0.0.0:" + m.network.port;
            } else if (t.startsWith("proxy_protocol:")) {
                line = indent + "proxy_protocol: " + m.network.proxyProtocol;
            } else if (t.startsWith("max_players:") && m.network.maxPlayers != null) {
                line = indent + "max_players: " + m.network.maxPlayers;
            }
            out.append(line).append('\n');
        }
        IoUtil.writeString(file, out.toString());
    }

    // ---- Utilitaires -----------------------------------------------------------------------------

    /** Authentification Mojang des joueurs sur le proxy (désactivable par {@code ksmc.online-mode: "false"}). */
    static boolean onlineMode(ServiceManifest m) {
        return m.properties == null || !"false".equals(m.properties.get("ksmc.online-mode"));
    }

    static void patchProperties(File file, Map<String, String> values) throws IOException {
        Map<String, String> remaining = new LinkedHashMap<String, String>(values);
        StringBuilder out = new StringBuilder();
        if (file.isFile()) {
            for (String line : IoUtil.readString(file).split("\r?\n")) {
                int eq = line.indexOf('=');
                String key = eq > 0 && !line.trim().startsWith("#") ? line.substring(0, eq).trim() : null;
                if (key != null && remaining.containsKey(key)) {
                    line = key + "=" + remaining.remove(key);
                }
                out.append(line).append('\n');
            }
        }
        for (Map.Entry<String, String> e : remaining.entrySet()) {
            out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        IoUtil.writeString(file, out.toString());
    }

    static void patchYaml(File file, String path, String value) throws IOException {
        String content = file.isFile() ? IoUtil.readString(file) : "";
        IoUtil.writeString(file, new YamlLines(content).set(path, value).toString());
    }

    /** Comparaison numérique de versions Minecraft (suffixes ignorés). */
    static int compare(String a, String b) {
        if (a == null) {
            return 1;
        }
        String[] x = a.split("[-+@ ]", 2)[0].split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parse(x[i]) : 0;
            int yi = i < y.length ? parse(y[i]) : 0;
            if (xi != yi) {
                return xi < yi ? -1 : 1;
            }
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
