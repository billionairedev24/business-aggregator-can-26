import type { ReactNode } from 'react';
import { SiteLink } from '@northline/ui';
import { Art, type Tone } from '../art/Art';

/** An illustrated entry tile (a category, department or cuisine): the picture, the name, an optional muted line. */
export function Tile({ href, name, subject, tone, meta, lang }: { href: string; name: string; subject: string; tone?: Tone; meta?: ReactNode; lang?: string }) {
  return (
    <SiteLink href={href} className="nl-tile">
      <span className="nl-tile-pic"><Art subject={subject} tone={tone} /></span>
      <span className="nl-tile-name" lang={lang}>{name}</span>
      {meta ? <span className="nl-tile-meta">{meta}</span> : null}
    </SiteLink>
  );
}
