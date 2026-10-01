import { useSyncExternalStore } from 'react';
import { currentRoleView, setRoleView } from '../../lib/http';
import type { RoleCode, RoleGrant } from './api';

/**
 * The console's role view (design "Switch role view"): the one held role the console acts with. The api receives it
 * as `X-Console-Role` on every call and checks the person holds it; the sidebar and route gates use its grant. Kept
 * per browser (localStorage) — a convenience, the api decides.
 */
const KEY = 'nl.console.role';
const listeners = new Set<() => void>();

const read = (): string | null => { try { return localStorage.getItem(KEY); } catch { return null; } };
const write = (role: string) => { try { localStorage.setItem(KEY, role); } catch { /* private mode */ } };

/** The stored view when still held, else the first held role in design order (admin first). */
export function initialRole(grants: readonly RoleGrant[]): RoleCode | undefined {
  const stored = read();
  return grants.find(g => g.role === stored)?.role ?? grants[0]?.role;
}

export function applyRole(role: RoleCode | undefined) {
  setRoleView(role);
  if (role) write(role);
  listeners.forEach(l => l());
}

const subscribe = (l: () => void) => { listeners.add(l); return () => { listeners.delete(l); }; };

/** The active role code (re-renders on a switch). */
export const useActiveRole = (): string | undefined => useSyncExternalStore(subscribe, currentRoleView, currentRoleView);
