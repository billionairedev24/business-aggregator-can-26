import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ProductCard, ServiceCard, StatusTag } from '@northline/ui';
import { tierTag } from '../shell/StudioLayout';
import { useShellT } from '../shell/messages';
import { listingsQuery, type Listing } from '../onboarding/listingsApi';
import type { Storefront } from './api';
import { sectionText, useStorefrontT, type StorefrontT } from './messages';
import type { SectionKind } from './sections';
import './StorefrontPreview.css';

type Row = { a: string; b: string };
type Block =
  | { kind: SectionKind; type: 'hero' | 'cta' }
  | { kind: SectionKind; type: 'rows'; rows: Row[] }
  | { kind: SectionKind; type: 'services' | 'products'; items: Listing[] }
  | { kind: SectionKind; type: 'text' | 'map'; text: string };

/** Up to three verified facts for the header (design 02 `site.stats`). */
export function heroStats(s: Storefront, t: StorefrontT): { v: string; k: string }[] {
  const out = [{ v: '—', k: t('noReviews') }];
  for (const f of s.business.verifiedFacts) {
    if (f.startsWith('licence:')) out.push({ v: f.slice('licence:'.length), k: t('verified') });
    else if (f === 'ahs_permit') out.push({ v: t('permitKind'), k: t('permitValid') });
    else if (f === 'insurance') out.push({ v: '$2M', k: t('insured') });
  }
  if (s.business.type === 'seller' && s.business.sameDayCutoff) out.push({ v: s.business.sameDayCutoff, k: t('cutoff') });
  return out.slice(0, 3);
}

function blocksFor(s: Storefront, services: Listing[], products: Listing[], t: StorefrontT): Block[] {
  const b = s.business;
  const verified = (f: string) => b.verifiedFacts.includes(f);
  const block = (kind: SectionKind): Block => {
    switch (kind) {
      case 'hero': case 'cta': return { kind, type: kind };
      case 'about': return { kind, type: 'text', text: b.about || t('aboutEmpty') };
      case 'services': return services.length ? { kind, type: 'services', items: services.slice(0, 3) } : { kind, type: 'text', text: t('servicesEmpty') };
      case 'featured': return products.length ? { kind, type: 'products', items: products.slice(0, 4) } : { kind, type: 'text', text: t('productsEmpty') };
      case 'catalogue': return products.length ? { kind, type: 'products', items: products.slice(0, 4) } : { kind, type: 'text', text: t('departmentsEmpty') };
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

/**
 * What customers see: the enabled sections in page order, in a phone frame or full width — drawn like the real
 * provider / store page (E-15 A · Plainsight, design/revamp/a.html screen 4; audit #10): a white header with the logo tile
 * (the business's colour as its edge), the name, the tagline and the verified tier, the figures, then the sections as
 * titled blocks and the action-blue book / order button. The business's colour is the tile's edge only, as on the page.
 * S-176: services and products are the consumer pages' own cards (@northline/ui ServiceCard / ProductCard: the photo in
 * the grey well or the dashed fallback, the one price format), inert — a picture of the page, not links.
 */
export function StorefrontPreview({ storefront: s, device }: { storefront: Storefront; device: 'phone' | 'web' }) {
  const t = useStorefrontT();
  const shellT = useShellT();
  const type = s.business.type;
  const services = useQuery({ ...listingsQuery(s.merchantId, 'service', 3), enabled: type === 'provider' || type === 'both' }).data ?? [];
  const products = useQuery({ ...listingsQuery(s.merchantId, 'product', 3), enabled: type === 'seller' || type === 'both' }).data ?? [];
  const initial = s.business.displayName.trim().charAt(0).toUpperCase() || 'N';
  const tier = tierTag({ tier: s.business.tier, status: s.business.status });
  const heading = (k: SectionKind): ReactNode => <div className="nl-pv-title">{sectionText(t, k, 'name')}</div>;

  return (
    <div className="nl-pv-frame" data-device={device} style={{ ['--nl-brand' as string]: s.brandColor }} aria-label={t('preview')} role="region">
      {blocksFor(s, services, products, t).map(b => (
        <div key={b.kind} className={b.type === 'cta' ? 'nl-pv-sticky' : undefined}>
          {b.type === 'hero' && <div className="nl-pv-hero">
            <div className="nl-pv-id">
              <div className="nl-pv-logo">{s.logo ? <img src={s.logo.url} alt={t('logoAlt', { name: s.business.displayName })} /> : initial}</div>
              <div className="nl-pv-idtext">
                <div className="nl-pv-name">{s.business.displayName}</div>
                {s.tagline ? <div className="nl-pv-tagline">{s.tagline}</div> : null}
              </div>
            </div>
            <div className="nl-pv-badges"><StatusTag tone={tier.cls === 'tag-success' ? 'success' : tier.cls === 'tag-warning' ? 'warning' : 'neutral'}>{shellT(tier.key)}</StatusTag></div>
            <dl className="nl-pv-stats">{heroStats(s, t).map(st => <div key={st.k}><dt>{st.k}</dt><dd>{st.v}</dd></div>)}</dl>
          </div>}
          {b.type === 'rows' && <div className="nl-pv-block">{heading(b.kind)}<div className="nl-pv-rows">{b.rows.map((r, i) => <div key={i} className="nl-pv-row"><span>{r.a}</span><strong>{r.b}</strong></div>)}</div></div>}
          {b.type === 'services' && <div className="nl-pv-block">{heading(b.kind)}<div className="nl-pv-cards" inert>{b.items.map(l => (
            <ServiceCard key={l.id} layout="row" level={4} name={l.name} kind={l.meta ?? undefined} place={s.business.displayName}
              priceCents={l.priceCents ?? null} priceText={l.priceCents == null ? t('quote') : undefined} />
          ))}</div></div>}
          {b.type === 'products' && <div className="nl-pv-block">{heading(b.kind)}<div className="nl-pv-products" data-device={device} inert>{b.items.map(l => (
            <ProductCard key={l.id} href="#" level={4} variant="rail" name={l.name} priceCents={l.priceCents ?? null} meta={s.business.displayName} lowStock={l.stock != null && l.stock > 0 && l.stock <= 5 ? l.stock : null} soldOut={l.stock === 0} />
          ))}</div></div>}
          {b.type === 'text' && <div className="nl-pv-block">{heading(b.kind)}<div className="nl-pv-text">{b.text}</div></div>}
          {b.type === 'map' && <div className="nl-pv-block">{heading(b.kind)}<div className="nl-pv-map">{b.text}</div></div>}
          {b.type === 'cta' && <div className="nl-pv-cta-wrap" inert><span className="btn btn-primary nl-pv-cta">{t(`cta_${s.ctaLabel}`)}</span></div>}
        </div>
      ))}
    </div>
  );
}
