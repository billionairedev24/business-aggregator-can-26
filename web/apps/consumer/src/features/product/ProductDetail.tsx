import { useEffect, useMemo, useState } from 'react';
import { useZone } from '../location/regions';
import { useSuspenseQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Minus, Plus } from '@phosphor-icons/react';
import { Alert, EmptyState, SiteLink, Skeleton, Tag, swatchOf, useFormatters, useLocale } from '@northline/ui';
import { clock, runWhen, weekday, windowRange } from '../shop/format';
import { useMarketFollowsLocation, withMarket } from '../shop/market';
import { TIER_TONE } from '../shop/parts';
import { productQuery, useAddToCart, type Offer, type ProductPage } from './api';
import { useProductT } from './messages';

/**
 * Product detail (S-50, design 06 `product`): gallery, name, the chosen shop (tier, rating), tags, price, description,
 * options (variants), quantity + Add to cart, the delivery panel with the cut-off, "Also from <shop>" — and, beyond the
 * design's single seller, every other shop of the market selling the same catalogue product ("Also sold by").
 */
export function ProductDetail({ productId, market: explicit, offerId }: { productId: string; market?: string; offerId?: string }) {
  const { locale } = useLocale();
  const t = useProductT();
  const { data } = useSuspenseQuery(productQuery(productId, explicit, locale));
  const zone = useZone(data.market);
  useMarketFollowsLocation(explicit, data.market);
  const link = (path: string) => withMarket(path, explicit);
  const offer = data.offers.find(o => o.offerId === offerId) ?? data.offers[0];

  const crumbs = (
    <nav aria-label={t('crumbs')} className="shop-crumbs">
      <ol>
        <li><SiteLink href={link('/shop')}>{t('shop')}</SiteLink></li>
        {data.departmentSlug ? <li><SiteLink href={link(`/shop/${data.departmentSlug}`)}>{data.departmentName}</SiteLink></li> : null}
        <li aria-current="page">{offer?.shopName ?? data.name}</li>
      </ol>
    </nav>
  );

  if (!offer) {
    return (
      <div className="nl-page product-page">
        {crumbs}
        <h1 className="product-title">{data.name}</h1>
        <div className="product-empty">
          {data.served
            ? <EmptyState action={<SiteLink href={link('/shop')} className="btn btn-primary">{t('backToShop')}</SiteLink>}>{t('unavailable', { city: data.market })}</EmptyState>
            : <EmptyState action={<SiteLink href="/location" className="btn btn-primary">{t('changeLocation')}</SiteLink>}>{t('notServed', { city: data.market })}</EmptyState>}
        </div>
      </div>
    );
  }
  return (
    <div className="nl-page product-page">
      {crumbs}
      <OfferView key={offer.offerId} data={data} offer={offer} link={link} />
    </div>
  );
}

function OfferView({ data, offer, link }: { data: ProductPage; offer: Offer; link: (p: string) => string }) {
  const t = useProductT();
  const { locale } = useLocale();
  const zone = useZone(data.market);
  const { money, number } = useFormatters();
  const navigate = useNavigate();
  const add = useAddToCart();
  const firstInStock = offer.variants.find(v => v.stock > 0) ?? offer.variants[0];
  const [variantId, setVariantId] = useState(firstInStock?.variantId);
  const variant = offer.variants.find(v => v.variantId === variantId);
  const stock = variant ? variant.stock : offer.stock;
  const price = variant ? variant.priceCents : offer.priceCents;
  const [qty, setQty] = useState(1);
  useEffect(() => { setQty(q => Math.max(1, Math.min(q, Math.max(1, stock)))); }, [stock]);
  const [image, setImage] = useState(0);
  const run = offer.runs[0];
  const tier = t(`tier_${offer.tier as 'master'}`);

  const windowText = (r: Offer['runs'][number]) => t(`win_${runWhen(r, zone)}`, { range: windowRange(r.startsAt, r.endsAt, locale, zone), weekday: weekday(r.startsAt, locale, zone) });
  const delivery = useMemo(() => {
    if (stock <= 0) return t('deliveryOut', { shop: offer.shopName });
    const eta = data.direct ? String(data.direct.etaMinutes) : 'none';
    if (!run) return t('deliveryNoRun', { eta });
    const second = offer.runs[1];
    return t('delivery', {
      orderBy: clock(run.orderBy, locale, zone), first: windowText(run), fee: run.feeCents === 0 ? t('free') : money(run.feeCents),
      second: second ? windowText(second) : 'none', eta, shop: offer.shopName, packBy: clock(run.packBy, locale, zone),
    });
  }, [stock, run, offer, data.direct, locale]); // eslint-disable-line react-hooks/exhaustive-deps

  const onAdd = () => add.mutate({ offerId: offer.offerId, variantId: variant?.variantId, qty }, { onSuccess: () => void navigate({ to: '/cart' }) });
  const others = data.offers.filter(o => o.offerId !== offer.offerId);

  return (
    <div className="product-grid">
      <div className="product-gallery" role="group" aria-label={t('gallery')}>
        <div className="product-main halftone">{offer.images[image] ? <img src={offer.images[image]} alt={data.name} /> : null}</div>
        <div className="product-thumbs">
          {offer.images.length > 1
            ? offer.images.slice(0, 4).map((src, i) => (
              <button key={src} type="button" className="product-thumb halftone" aria-label={t('showImage', { n: i + 1 })} aria-pressed={i === image} onClick={() => setImage(i)}><img src={src} alt="" /></button>))
            : [0, 1, 2].map(i => <span key={i} className={`product-thumb halftone product-thumb-${i}`} aria-hidden />)}
        </div>
      </div>

      <div className="product-info">
        <h1 className="product-title">{data.name}</h1>
        <div className="product-byline">
          <SiteLink href={`/search?scope=shop&q=${encodeURIComponent(offer.shopName)}`}>{offer.shopName}</SiteLink>
          {' · '}{t('meta', { tier })}
          {offer.ratingCount > 0 ? <>{' · '}{t('rating', { average: number(offer.rating, { minimumFractionDigits: 1, maximumFractionDigits: 1 }), count: offer.ratingCount })}</> : null}
        </div>
        <div className="product-tags">
          {run && stock > 0 ? <Tag>{t(`tag_${runWhen(run, zone)}`, { weekday: weekday(run.startsAt, locale, zone) })}</Tag> : null}
          {stock <= 0 ? <Tag tone="neutral">{t('outOfStock')}</Tag> : offer.lowStock || stock <= 3 ? <Tag tone="accent-2">{t('lowStock', { count: stock })}</Tag> : null}
          {offer.returnsPolicy === 'standard_14' || offer.returnsPolicy === 'final_sale' ? <Tag tone="neutral">{t(`returns_${offer.returnsPolicy}`)}</Tag> : null}
        </div>
        <div className="product-price">
          <span className="product-price-value">{money(price)}</span>
          {offer.compareAtCents && offer.compareAtCents > price ? <s className="product-was">{t('wasPrice', { price: money(offer.compareAtCents) })}</s> : null}
          <span className="product-price-note">{data.unit ? t('priceNote', { unit: data.unit }) : t('priceNoteNoUnit')}</span>
        </div>
        {data.description ? <p className="product-desc">{data.description}</p> : null}
        {data.bullets.length > 0 ? <ul className="product-bullets">{data.bullets.map(b => <li key={b}>{b}</li>)}</ul> : null}

        {offer.variants.length > 0 ? (
          <fieldset className="product-options">
            <legend>{t('options')}</legend>
            <div className="product-option-list">
              {offer.variants.map(v => (
                <button key={v.variantId} type="button" className="shop-chip" aria-pressed={v.variantId === variantId} disabled={v.stock <= 0}
                  onClick={() => setVariantId(v.variantId)}>{v.value}</button>
              ))}
            </div>
          </fieldset>
        ) : null}

        <div className="product-buy">
          <div className="product-qty" role="group" aria-label={t('qty')}>
            <button type="button" aria-label={t('dec')} disabled={qty <= 1} onClick={() => setQty(q => Math.max(1, q - 1))}><Minus size={18} aria-hidden /></button>
            <span aria-live="polite">{qty}</span>
            <button type="button" aria-label={t('inc')} disabled={qty >= stock} onClick={() => setQty(q => Math.min(stock, q + 1))}><Plus size={18} aria-hidden /></button>
          </div>
          <button type="button" className="btn btn-primary product-add" disabled={stock <= 0 || add.isPending} aria-busy={add.isPending} onClick={onAdd}>
            {add.isPending ? t('adding') : t('add', { qty, total: money(price * qty) })}
          </button>
        </div>
        {add.isError ? <div className="product-alert"><Alert tone="error" role="alert">{t('addFailed')}</Alert></div> : null}

        <div className="product-delivery"><strong>{t('deliveryLabel')}</strong> {delivery}</div>

        {offer.more.length > 0 ? (
          <section aria-labelledby="product-more">
            <h2 id="product-more" className="product-h3">{t('alsoFrom', { shop: offer.shopName })}</h2>
            <ul className="product-more">
              {offer.more.map(m => <li key={m.productId}><SiteLink href={link(`/products/${m.productId}`)}>{t('moreItem', { name: m.name, price: money(m.priceCents) })}</SiteLink></li>)}
            </ul>
          </section>
        ) : null}

        {others.length > 0 ? (
          <section aria-labelledby="product-sellers">
            <h2 id="product-sellers" className="product-h3">{t('sellers')}</h2>
            <ul className="product-sellers">
              {others.map(o => {
                const r = o.runs[0];
                const when = o.stock <= 0 ? t('outOfStock') : r ? t(`tag_${runWhen(r, zone)}`, { weekday: weekday(r.startsAt, locale, zone) }) : t('notOnRunShort');
                return (
                  <li key={o.offerId} className="product-seller">
                    <span className={`product-seller-mark nl-swatch-${swatchOf(o.merchantId)}`} aria-hidden>{o.shopName.charAt(0).toUpperCase()}</span>
                    <span className="product-seller-body">
                      <strong>{o.shopName}</strong>
                      <span className="product-seller-meta"><span className={`tag tag-${TIER_TONE[o.tier] ?? 'neutral'}`}>{t(`tier_${o.tier as 'master'}`)}</span> {when}</span>
                    </span>
                    <span className="product-seller-price">{money(o.priceCents)}</span>
                    <SiteLink href={link(`/products/${data.productId}?offer=${o.offerId}`)} className="btn btn-secondary" aria-label={`${t('choose')} — ${o.shopName}`}>{t('choose')}</SiteLink>
                  </li>
                );
              })}
            </ul>
          </section>
        ) : null}
      </div>
    </div>
  );
}

export function ProductSkeleton() {
  const t = useProductT();
  return (
    <div className="nl-page product-page" aria-busy="true">
      <span className="nl-sr-only">{t('loading')}</span>
      <Skeleton width={260} height={12} />
      <div className="product-grid">
        <div><Skeleton height={0} radius={12} style={{ aspectRatio: '1 / 1', height: 'auto' }} /></div>
        <div>
          <Skeleton width="70%" height={36} /><Skeleton width="50%" height={14} style={{ marginTop: 10 }} />
          <Skeleton width={120} height={32} style={{ marginTop: 22 }} /><Skeleton height={60} style={{ marginTop: 16 }} />
          <Skeleton width={260} height={46} style={{ marginTop: 24 }} /><Skeleton height={70} style={{ marginTop: 22 }} />
        </div>
      </div>
    </div>
  );
}
