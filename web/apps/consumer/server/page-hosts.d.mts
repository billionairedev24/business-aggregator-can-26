export const PAGE_HEADERS: string[];
export function normalizeHost(value: string | undefined | null): string;
export interface PageHost { mode: 'custom' | 'pages'; host: string; slug: string }
export type PageRoute = { type: 'app'; page?: PageHost } | { type: 'redirect'; location: string } | { type: 'notFound' } | { type: 'unavailable' };
export function createPageRouter(options?: { siteOrigin?: string; pagesHost?: string; bffUrl?: string; fetch?: typeof fetch; ttlMs?: number; now?: () => number }):
  (host: string, pathname: string, search?: string) => Promise<PageRoute>;
