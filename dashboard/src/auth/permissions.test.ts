import { grants } from './permissions';

describe('grants', () => {
  it('suit les règles du controller', () => {
    expect(grants(['*'], 'ksmc.task.write')).toBe(true);
    expect(grants(['ksmc.task.write'], 'ksmc.task.write')).toBe(true);
    expect(grants(['ksmc.task.*'], 'ksmc.task.write')).toBe(true);
    expect(grants(['ksmc.*'], 'ksmc.service.console')).toBe(true);
    expect(grants(['ksmc.task.read'], 'ksmc.task.write')).toBe(false);
    expect(grants(['ksmc.task.*'], 'ksmc.taskx.write')).toBe(false);
    expect(grants([], undefined)).toBe(true);
  });
});
