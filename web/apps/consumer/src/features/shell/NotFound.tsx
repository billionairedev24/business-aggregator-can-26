import { Compass, MagnifyingGlass } from '@phosphor-icons/react';
import { SiteLink } from '@northline/ui';
import { Scene } from '../art/Scene';
import { useShellT } from './messages';

/** No such page: a picture, what happened, and the ways back (home, Services, Shop). */
export function NotFound() {
  const t = useShellT();
  return (
    <div className="nl-page nl-lost">
      <span className="nl-lost-pic" aria-hidden><Scene slot="empty/not-found" subject="lost" tone="linen" draw={{ main: Compass, side: MagnifyingGlass }} fit="contain" sizes="(min-width: 768px) 560px, 100vw" priority /></span>
      <h1 className="nl-lost-title">{t('notFoundTitle')}</h1>
      <p className="nl-lost-body">{t('notFoundBody')}</p>
      <div className="nl-lost-actions">
        <SiteLink href="/" className="btn btn-primary btn-lg">{t('backHome')}</SiteLink>
        <SiteLink href="/services" className="btn btn-secondary btn-lg">{t('services')}</SiteLink>
        <SiteLink href="/shop" className="btn btn-secondary btn-lg">{t('shop')}</SiteLink>
      </div>
    </div>
  );
}
