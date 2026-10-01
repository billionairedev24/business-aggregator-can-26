import type { HostKind } from './page-hosts.mjs';
export interface SeoAnswer { status: number; type: string; body: string; cache: string }
export const DISALLOW: string[];
export function localized(url: string, locale: 'en' | 'fr'): string;
export function robotsTxt(kind: HostKind['kind'], origin: string): string;
export function urlset(entries: { loc: string; lastmod?: string; alternates?: boolean }[]): string;
export function sitemapIndex(locs: string[]): string;
export function isSeoPath(pathname: string): boolean;
export function createSeo(options: { siteOrigin: string; bffUrl: string; fetch?: typeof fetch; ttlMs?: number; now?: () => number }):
  (host: HostKind, pathname: string) => Promise<SeoAnswer>;
