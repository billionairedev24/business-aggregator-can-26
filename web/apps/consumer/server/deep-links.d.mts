import type { HostKind } from './page-hosts.mjs';
export function webPageOf(pathname: string): string | null;
export function isDeepLinkPath(pathname: string): boolean;
export function deepLinkAnswer(host: HostKind, pathname: string): { status: number; headers: Record<string, string> } | null;
