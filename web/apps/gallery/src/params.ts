import type { Locale } from '@northline/ui';

/** `?lang=fr` (else en), `?section=<id>` (else every section), `?open=dialog|sheet` (the overlays section opened). */
export function galleryParams(search: string): { locale: Locale; section: string | null; open: string | null } {
  const q = new URLSearchParams(search);
  return { locale: q.get('lang') === 'fr' ? 'fr' : 'en', section: q.get('section'), open: q.get('open') };
}
