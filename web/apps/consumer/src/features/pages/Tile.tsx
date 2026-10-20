import type { ReactNode } from 'react';
import { SiteLink } from '@northline/ui';
import { Scene } from '../art/Scene';
import type { Tone } from '../art/Art';

/**
 * An entry tile (a category, department or cuisine): its photo (the slot's, square), the name, an optional muted
 * line. The drawn picture of `subject` shows only while the photo library has none for the slot.
 */
export function Tile({ href, name, slot, subject, tone, meta, lang }: { href: string; name: string; slot: string | readonly (string | null | undefined)[]; subject: string; tone?: Tone; meta?: ReactNode; lang?: string }) {
  return (
    <SiteLink href={href} className="nl-tile">
      <span className="nl-tile-pic"><Scene slot={slot} subject={subject} tone={tone} crop="square" sizes="(min-width: 960px) 180px, 40vw" /></span>
      <span className="nl-tile-name" lang={lang}>{name}</span>
      {meta ? <span className="nl-tile-meta">{meta}</span> : null}
    </SiteLink>
  );
}
