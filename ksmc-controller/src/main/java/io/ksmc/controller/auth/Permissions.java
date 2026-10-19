package io.ksmc.controller.auth;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Permissions de l'API ({@code ksmc.<ressource>.<action>}) et rôles par défaut. Joker : {@code *} (tout) ou
 * {@code préfixe.*} (ex. {@code ksmc.service.*}). Les modules déclarent les leurs ({@code <module>.<action>}).
 */
public final class Permissions {

    public static final String ALL = "*";

    public static final String TASK_READ = "ksmc.task.read";
    public static final String TASK_WRITE = "ksmc.task.write";
    public static final String TASK_START = "ksmc.task.start";
    public static final String GROUP_READ = "ksmc.group.read";
    public static final String GROUP_WRITE = "ksmc.group.write";
    public static final String TEMPLATE_READ = "ksmc.template.read";
    public static final String TEMPLATE_WRITE = "ksmc.template.write";
    public static final String SERVICE_READ = "ksmc.service.read";
    public static final String SERVICE_WRITE = "ksmc.service.write";
    public static final String SERVICE_COMMAND = "ksmc.service.command";
    public static final String SERVICE_CONSOLE = "ksmc.service.console";
    public static final String SOFTWARE_READ = "ksmc.software.read";
    public static final String SOFTWARE_WRITE = "ksmc.software.write";
    public static final String MODULE_READ = "ksmc.module.read";
    public static final String MODULE_WRITE = "ksmc.module.write";
    public static final String PLAYER_READ = "ksmc.player.read";
    public static final String PLAYER_ACTION = "ksmc.player.action";
    public static final String EVENT_READ = "ksmc.event.read";
    public static final String AUDIT_READ = "ksmc.audit.read";
    public static final String ACCOUNT_ADMIN = "ksmc.account.admin";

    /** Permissions intégrées, pour la liste {@code GET /api/v1/permissions}. */
    public static final List<String> BUILTIN = List.of(TASK_READ, TASK_WRITE, TASK_START, GROUP_READ, GROUP_WRITE,
            TEMPLATE_READ, TEMPLATE_WRITE, SERVICE_READ, SERVICE_WRITE, SERVICE_COMMAND, SERVICE_CONSOLE, SOFTWARE_READ,
            SOFTWARE_WRITE, MODULE_READ, MODULE_WRITE, PLAYER_READ, PLAYER_ACTION, EVENT_READ, AUDIT_READ, ACCOUNT_ADMIN);

    public static final List<String> VIEWER = List.of(TASK_READ, GROUP_READ, TEMPLATE_READ, SERVICE_READ, SOFTWARE_READ,
            MODULE_READ, PLAYER_READ, EVENT_READ);

    public static final List<String> OPERATOR = List.of("ksmc.task.*", "ksmc.group.*", "ksmc.template.*",
            "ksmc.service.*", SOFTWARE_READ, MODULE_READ, "ksmc.player.*", EVENT_READ);

    /** Rôles créés à l'installation (modifiables ensuite, recréés seulement s'ils sont supprimés). */
    public static Map<String, List<String>> defaultRoles() {
        Map<String, List<String>> roles = new LinkedHashMap<>();
        roles.put("admin", List.of(ALL));
        roles.put("operator", OPERATOR);
        roles.put("viewer", VIEWER);
        return roles;
    }

    private Permissions() {
    }

    /** Vrai si l'une des permissions accordées couvre {@code required} ({@code null} : toujours vrai). */
    public static boolean grants(Collection<String> granted, String required) {
        if (required == null || granted.contains(ALL) || granted.contains(required)) {
            return true;
        }
        for (int i = required.lastIndexOf('.'); i > 0; i = required.lastIndexOf('.', i - 1)) {
            if (granted.contains(required.substring(0, i) + ".*")) {
                return true;
            }
        }
        return false;
    }
}
