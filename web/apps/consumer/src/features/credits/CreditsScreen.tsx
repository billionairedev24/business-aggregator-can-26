import { credits, photoFor, type Credit } from '@northline/media';
import { Photo, useLocale } from '@northline/ui';
import { useCreditsT } from './messages';

const SITE_NAME: Record<string, string> = { pexels: 'Pexels', unsplash: 'Unsplash', openverse: 'Openverse' };
/** Each site's own licence page (Openverse photos link their Creative Commons deed). */
const LICENCE_PAGE: Record<string, string> = { 'Pexels License': 'https://www.pexels.com/license/', 'Unsplash License': 'https://unsplash.com/license' };

/**
 * Photo credits: every stock photograph in the library (photos.json) — a thumbnail, what it shows, the photographer,
 * where it was found and its licence, linked — in slot order. Empty until the library has photos.
 */
export function CreditsScreen({ list = credits() }: { list?: Credit[] }) {
  const t = useCreditsT();
  return (
    <div className="nl-page nl-credits">
      <h1 className="nl-band-title">{t('heading')}</h1>
      <p className="nl-credits-lede">{t('lede')}</p>
      {list.length === 0 ? <p className="nl-credits-none">{t('none')}</p> : (
        <>
          <p className="nl-credits-count">{t('count', { count: list.length })}</p>
          <ul className="nl-credits-list">{list.map(c => <CreditRow key={c.slot} credit={c} />)}</ul>
        </>
      )}
    </div>
  );
}

function CreditRow({ credit: c }: { credit: Credit }) {
  const t = useCreditsT();
  const { locale } = useLocale();
  const photo = photoFor(c.slot);
  const what = photo?.alt[locale] ?? c.title ?? c.slot;
  const site = c.source ? `${SITE_NAME[c.site] ?? c.site} · ${c.source}` : SITE_NAME[c.site] ?? c.site;
  const licenceHref = c.licenceUrl ?? LICENCE_PAGE[c.licence];
  return (
    <li className="nl-credits-row">
      <span className="nl-credits-thumb">{photo ? <Photo photo={photo} crop="square" sizes="96px" decorative /> : null}</span>
      <span className="nl-credits-text">
        <span className="nl-credits-what">{c.title && c.title !== what ? `${what} — “${c.title}”` : what}</span>
        <span className="nl-credits-meta">
          {c.photographer ? (c.photographerUrl ? <a href={c.photographerUrl} rel="noopener">{t('by', { name: c.photographer })}</a> : t('by', { name: c.photographer })) : t('byUnknown')}
          {' · '}{t('on', { site })}
          {' · '}{licenceHref ? <a href={licenceHref} rel="noopener license">{t('licence', { licence: c.licence })}</a> : t('licence', { licence: c.licence })}
          {' · '}<a href={c.sourceUrl} rel="noopener" aria-label={t('pageOf', { what })}>{t('page')}</a>
        </span>
      </span>
    </li>
  );
}
