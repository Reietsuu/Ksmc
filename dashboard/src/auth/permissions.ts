/** Mêmes règles que Permissions.grants côté controller : "*", nom exact ou préfixe "a.b.*". */
export function grants(granted: Iterable<string>, required: string | undefined | null): boolean {
  const set = granted instanceof Set ? (granted as Set<string>) : new Set(granted);
  if (!required || set.has('*') || set.has(required)) return true;
  for (let i = required.lastIndexOf('.'); i > 0; i = required.lastIndexOf('.', i - 1)) {
    if (set.has(required.substring(0, i) + '.*')) return true;
  }
  return false;
}

export const P = {
  TASK_READ: 'ksmc.task.read',
  TASK_WRITE: 'ksmc.task.write',
  TASK_START: 'ksmc.task.start',
  GROUP_READ: 'ksmc.group.read',
  GROUP_WRITE: 'ksmc.group.write',
  TEMPLATE_READ: 'ksmc.template.read',
  TEMPLATE_WRITE: 'ksmc.template.write',
  SERVICE_READ: 'ksmc.service.read',
  SERVICE_WRITE: 'ksmc.service.write',
  SERVICE_COMMAND: 'ksmc.service.command',
  SERVICE_CONSOLE: 'ksmc.service.console',
  SOFTWARE_READ: 'ksmc.software.read',
  SOFTWARE_WRITE: 'ksmc.software.write',
  MODULE_READ: 'ksmc.module.read',
  MODULE_WRITE: 'ksmc.module.write',
  PLAYER_READ: 'ksmc.player.read',
  PLAYER_ACTION: 'ksmc.player.action',
  EVENT_READ: 'ksmc.event.read',
  AUDIT_READ: 'ksmc.audit.read',
  ACCOUNT_ADMIN: 'ksmc.account.admin',
} as const;
