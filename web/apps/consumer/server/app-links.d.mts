import type { HostKind } from './page-hosts.mjs';
export interface AppLinkAnswer { status: number; headers: Record<string, string>; body: string }
export interface AndroidApp { packageName: string; fingerprints: string[] }
export const CONSUMER_PATHS: string[];
export const COURIER_PATHS: string[];
export function isAppLinkPath(pathname: string): boolean;
export function appleAppSiteAssociation(teamId: string, bundleIds: string[]): { applinks: { details: { appIDs: string[]; components: { '/': string }[] }[] } };
export function assetLinks(apps: AndroidApp[]): unknown[];
export function parseAndroidApps(text: string | undefined): AndroidApp[];
export function createAppLinks(options?: { appleTeamId?: string; iosApps?: string; androidApps?: string }):
  (host: HostKind, pathname: string) => AppLinkAnswer | null;
