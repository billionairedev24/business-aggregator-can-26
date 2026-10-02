export interface AppLinksConfig { appleTeamId: string; iosBundleIds: string[]; androidPackages: string[]; androidCertSha256: string[] }
export interface AppLinkAnswer { status: number; type: string; cache: string; body: string; referrerPolicy?: string }
export const AASA_PATH: string;
export const ASSETLINKS_PATH: string;
export const REDIRECT_PATHS: string[];
export const DEFAULT_APP_IDS: string[];
export function pathPrefixFor(id: string): '/app' | '/courier';
export function isAppLinkPath(pathname: string): boolean;
export function appLinksConfig(env?: Record<string, string | undefined>): AppLinksConfig;
export function appleAppSiteAssociation(config: Pick<AppLinksConfig, 'appleTeamId' | 'iosBundleIds'>): {
  applinks: { details: { appIDs: string[]; components: { '/': string; comment: string }[] }[] };
  webcredentials: { apps: string[] };
};
export function assetLinks(config: Pick<AppLinksConfig, 'androidPackages' | 'androidCertSha256'>): {
  relation: string[]; target: { namespace: string; package_name: string; sha256_cert_fingerprints: string[] };
}[];
export function appLinkAnswer(pathname: string, config: AppLinksConfig): AppLinkAnswer | null;
