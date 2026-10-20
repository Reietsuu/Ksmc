import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../auth/session';

/** Ressource Kubernetes telle que renvoyée par /api/v1/<type>. */
export interface Resource<S = Record<string, unknown>, St = unknown> {
  apiVersion?: string;
  kind?: string;
  metadata: { name: string; resourceVersion?: string; labels?: Record<string, string>; creationTimestamp?: string };
  spec: S;
  status?: St;
}

export interface ResourceList<T> {
  items: T[];
  continue: string | null;
}

export type Kind = 'tasks' | 'groups' | 'templates' | 'services' | 'software' | 'modules';

export const API_VERSION = 'ksmc.io/v1alpha1';
export const KIND_NAMES: Record<Kind, string> = {
  tasks: 'ServiceTask',
  groups: 'ServiceGroup',
  templates: 'ServiceTemplate',
  services: 'CloudService',
  software: 'SoftwareDefinition',
  modules: 'KsmcModule',
};

export interface TaskSpec {
  environment: 'server' | 'proxy';
  software: { name: string; version?: string; image?: string };
  memoryMb?: number;
  minServiceCount?: number;
  maxServiceCount?: number;
  maintenance?: boolean;
  static?: boolean;
  enabled?: boolean;
  templates?: { name: string; optional?: boolean }[];
  groups?: string[];
  [key: string]: unknown;
}

export interface TaskStatus {
  readyServices?: number;
  totalServices?: number;
  onlinePlayers?: number;
  phases?: Record<string, number>;
  conditions?: { type: string; status: string; reason?: string; message?: string }[];
}

export interface ServiceStatus {
  phase?: string;
  onlinePlayers?: number;
  maxPlayers?: number;
  podName?: string;
  address?: string;
  softwareVersion?: string;
  startedAt?: string;
  readyAt?: string;
  message?: string;
  reason?: string;
  tps?: number;
  motd?: string;
  restartCount?: number;
}

export type Task = Resource<TaskSpec, TaskStatus>;
export type Service = Resource<{ task?: string; environment?: string; desiredState?: string; software?: { name: string; version?: string }; static?: boolean }, ServiceStatus>;

/** Nouvelle ressource prête à envoyer (apiVersion/kind remplis). */
export function newResource<S>(kind: Kind, name: string, spec: S): Resource<S, never> {
  return { apiVersion: API_VERSION, kind: KIND_NAMES[kind], metadata: { name }, spec };
}

export function useList<T = Resource>(kind: Kind, params: Record<string, string> = {}, enabled = true) {
  const api = useApi();
  const qs = new URLSearchParams({ limit: '500', ...params }).toString();
  return useQuery({
    queryKey: [kind, 'list', params],
    queryFn: () => api.json<ResourceList<T>>(`/api/v1/${kind}?${qs}`),
    enabled,
    refetchInterval: 15_000,
  });
}

export function useOne<T = Resource>(kind: Kind, name: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: [kind, 'one', name],
    queryFn: () => api.json<T>(`/api/v1/${kind}/${encodeURIComponent(name!)}`),
    enabled: !!name,
  });
}

/** Création, remplacement (If-Match = resourceVersion), suppression et actions ; invalide le type concerné. */
export function useResourceMutations(kind: Kind) {
  const api = useApi();
  const qc = useQueryClient();
  const done = () => qc.invalidateQueries({ queryKey: [kind] });
  const path = (name: string) => `/api/v1/${kind}/${encodeURIComponent(name)}`;
  return {
    create: useMutation({
      mutationFn: (r: Resource<unknown, unknown>) => api.json<Resource>(`/api/v1/${kind}`, { method: 'POST', body: JSON.stringify(r) }),
      onSuccess: done,
    }),
    replace: useMutation({
      mutationFn: (r: Resource<unknown, unknown>) =>
        api.json<Resource>(path(r.metadata.name), {
          method: 'PUT',
          body: JSON.stringify(r),
          headers: r.metadata.resourceVersion ? { 'If-Match': `"${r.metadata.resourceVersion}"` } : {},
        }),
      onSuccess: done,
    }),
    patch: useMutation({
      mutationFn: ({ name, body }: { name: string; body: unknown }) =>
        api.json<Resource>(path(name), {
          method: 'PATCH',
          body: JSON.stringify(body),
          headers: { 'Content-Type': 'application/merge-patch+json' },
        }),
      onSuccess: done,
    }),
    remove: useMutation({
      mutationFn: (name: string) => api.json(path(name), { method: 'DELETE' }),
      onSuccess: done,
    }),
    action: useMutation({
      mutationFn: ({ name, action, body = {} }: { name: string; action: string; body?: unknown }) =>
        api.json(`${path(name)}/${action}`, { method: 'POST', body: JSON.stringify(body) }),
      onSuccess: () => {
        done();
        qc.invalidateQueries({ queryKey: ['services'] });
      },
    }),
  };
}
