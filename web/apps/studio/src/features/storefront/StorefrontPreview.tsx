import type { CSSProperties, ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { formatMoney, useLocale } from '@northline/ui';
import { tierTag } from '../shell/StudioLayout';
import { useShellT } from '../shell/messages';
import { listingsQuery, type Listing } from '../onboarding/listingsApi';
import type { Storefront } from './api';
import { sectionText, useStorefrontT, type StorefrontT } from './messages';
import type { SectionKind } from './sections';

type Row = { a: string; b: string };
type Block =
  | { kind: SectionKind; type: 'hero' | 'cta' }
  | { kind: SectionKind; type: 'rows'; rows: Row[] }
  | { kind: SectionKind; type: 'text' | 'map'; text: string };

/** Up to three verified facts for the header (design 02 `site.stats`). */
export function heroStats(s: Storefront, t: StorefrontT): { v: string; k: string }[] {
  const out = [{ v: '—', k: t('noReviews') }];
  for (const f of s.business.verifiedFacts) {
    if (f.startsWith('licence:')) out.push({ v: f.slice('licence:'.length), k: t('verified') });
    else if (f === 'ahs_permit') out.push({ v: 'AHS', k: t('permitValid') });
    else if (f === 'insurance') out.push({ v: '$2M', k: t('insured') });
  }
  if (s.business.type === 'seller' && s.business.sameDayCutoff) out.push({ v: s.business.sameDayCutoff, k: t('cutoff') });
  return out.slice(0, 3);
}

function rowsOf(list: Listing[], money: (c: number) => string, t: StorefrontT): Row[] {
  return list.slice(0, 3).map(l => ({ a: l.name, b: l.priceCents == null ? t('quote') : money(l.priceCents) }));
}

function blocksFor(s: Storefront, services: Listing[], products: Listing[], t: StorefrontT, money: (c: number) => string): Block[] {
  const b = s.business;
  const verified = (f: string) => b.verifiedFacts.includes(f);
  const block = (kind: SectionKind): Block => {
    switch (kind) {
      case 'hero': case 'cta': return { kind, type: kind };
      case 'about': return { kind, type: 'text', text: b.about || t('aboutEmpty') };
      case 'services': return services.length ? { kind, type: 'rows', rows: rowsOf(services, money, t) } : { kind, type: 'text', text: t('servicesEmpty') };
      case 'featured': return products.length ? { kind, type: 'rows', rows: rowsOf(products, money, t) } : { kind, type: 'text', text: t('productsEmpty') };
      case 'catalogue': return products.length ? { kind, type: 'rows', rows: rowsOf(products, money, t) } : { kind, type: 'text', text: t('departmentsEmpty') };
      case 'menu': return { kind, type: 'text', text: t('menuEmpty') };
      case 'reviews': return { kind, type: 'rows', rows: [{ a: t('newOnNorthline'), b: t('verifiedTag') }, ...(b.type === 'provider' || b.type === 'both' ? [{ a: t('onTime'), b: '—' }] : [])] };
      case 'area': return { kind, type: 'map', text: b.serviceArea || t('areaEmpty') };
      case 'delivery': return { kind, type: 'map', text: b.sameDayCutoff ? t('deliveryText', { cutoff: b.sameDayCutoff }) : t('deliveryEmpty') };
      case 'gallery': return { kind, type: 'text', text: t('galleryEmpty') };
      case 'faq': {
        const pairs = Array.isArray(s.sections.find(x => x.kind === 'faq')?.settings.pairs) ? (s.sections.find(x => x.kind === 'faq')!.settings.pairs as { q?: string }[]) : [];
        return pairs.length ? { kind, type: 'rows', rows: pairs.slice(0, 3).map(p => ({ a: String(p.q ?? ''), b: '+' })) } : { kind, type: 'text', text: t('faqEmpty') };
      }
      case 'policies': {
        const text = s.sections.find(x => x.kind === 'policies')?.settings.returns_text;
        return { kind, type: 'text', text: typeof text === 'string' && text ? text : t('policiesEmpty') };
      }
      case 'hours': return { kind, type: 'rows', rows: [{ a: t('hoursToday'), b: '—' }, { a: t('hoursPrep'), b: '—' }] };
      case 'fulfil': return { kind, type: 'map', text: t('fulfilEmpty') };
      case 'permit': return { kind, type: 'rows', rows: [{ a: t('permitRow'), b: verified('ahs_permit') ? t('valid') : t('pending') }, { a: t('visitRow'), b: verified('site_visit') ? t('passed') : t('pending') }] };
    }
  };
  return s.sections.filter(x => x.enabled).map(x => block(x.kind));
}

/** What customers see: the enabled sections in page order, in a phone frame or full width. */
export function StorefrontPreview({ storefront: s, device }: { storefront: Storefront; device: 'phone' | 'web' }) {
  const t = useStorefrontT();
  const shellT = useShellT();
  const { locale } = useLocale();
  const money = (c: number) => formatMoney(c, locale, { whole: c % 100 === 0 });
  const type = s.business.type;
  const services = useQuery({ ...listingsQuery(s.merchantId, 'service', 3), enabled: type === 'provider' || type === 'both' }).data ?? [];
  const products = useQuery({ ...listingsQuery(s.merchantId, 'product', 3), enabled: type === 'seller' || type === 'both' }).data ?? [];
  const brand = { ['--nl-brand' as string]: s.brandColor } as CSSProperties;
  const initial = s.business.displayName.trim().charAt(0).toUpperCase() || 'N';
  const tier = tierTag({ tier: s.business.tier, status: s.business.status });
  const heading = (k: SectionKind): ReactNode => <div className="nl-pv-kicker">{sectionText(t, k, 'name')}</div>;

  return (
    <div className="nl-pv-frame" data-device={device} style={brand} aria-label={t('preview')} role="region">
      {blocksFor(s, services, products, t, money).map(b => (
        <div key={b.kind} className={b.type === 'cta' ? 'nl-pv-sticky' : undefined}>
          {b.type === 'hero' && <>
            <div className="nl-pv-hero">
              <div className="nl-pv-hero-top">
                <div className="nl-pv-logo">{s.logo ? <img src={s.logo.url} alt={t('logoAlt', { name: s.business.displayName })} /> : initial}</div>
                <span className="tag nl-pv-tier">{shellT(tier.key)}</span>
              </div>
              <div className="nl-pv-name">{s.business.displayName}</div>
              {s.tagline ? <div className="nl-pv-tagline">{s.tagline}</div> : null}
            </div>
            <div className="nl-pv-stats">{heroStats(s, t).map(st => <div key={st.k}><strong>{st.v}</strong><br />{st.k}</div>)}</div>
          </>}
          {b.type === 'rows' && <div className="nl-pv-block">{heading(b.kind)}{b.rows.map((r, i) => <div key={i} className="nl-pv-row"><span>{r.a}</span><strong>{r.b}</strong></div>)}</div>}
          {b.type === 'text' && <div className="nl-pv-block">{heading(b.kind)}<div className="nl-pv-text">{b.text}</div></div>}
          {b.type === 'map' && <div className="nl-pv-block">{heading(b.kind)}<div className="nl-pv-map">{b.text}</div></div>}
          {b.type === 'cta' && <div className="nl-pv-cta-wrap"><div className="nl-pv-cta">{t(`cta_${s.ctaLabel}`)}</div></div>}
        </div>
      ))}
    </div>
  );
}
