import { useEffect, useMemo, useState } from 'react';
import { useZone } from '../location/regions';
import { useSuspenseQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Alert, EmptyState, Price, QuantityStepper, SiteLink, Skeleton, Stars, swatchOf, useFormatters, useLocale } from '@northline/ui';
import { clock, runWhen, weekday, windowRange } from '../shop/format';
import { useMarketFollowsLocation, withMarket } from '../shop/market';
import { productQuery, useAddToCart, type Offer, type ProductPage } from './api';
import { Crumbs } from '../pages/Crumbs';
import { RailSection, RailTrack } from '../pages/Rail';
import { departmentSlot } from '@northline/media';
import { Scene } from '../art/Scene';
import { PlaceButton } from '../pages/PlaceButton';
import { useProductT } from './messages';
import { usePlusBadge } from '../loyalty/PlusBadge';

/**
 * Product detail (redesign § 1.3 "PDP", AT Shop:342–423; S-50 behaviour): thumbnails | the sticky image | the info
 * column (title, "Visit the shop", stars, price, the variants, a facts grid, "About this item") | a 300px sticky buy box
 * (price, the delivery promise with the cut-off, stock, quantity, Add to cart, Buy now, who sells it, returns, and the
 * other shops of the market selling the same catalogue product). "Also from <shop>" follows.
 */
export function ProductDetail({ productId, market: explicit, offerId }: { productId: string; market?: string; offerId?: string }) {
  const { locale } = useLocale();
  const t = useProductT();
  const { data } = useSuspenseQuery(productQuery(productId, explicit, locale));
  useMarketFollowsLocation(explicit, data.market);
  const link = (path: string) => withMarket(path, explicit);
  const offer = data.offers.find(o => o.offerId === offerId) ?? data.offers[0];

  const crumbs = (
    <Crumbs label={t('crumbs')} items={[
      { label: t('shop'), href: link('/shop') },
      ...(data.departmentSlug ? [{ label: data.departmentName ?? data.departmentSlug, href: link(`/shop/${data.departmentSlug}`) }] : []),
      { label: data.name },
    ]} />
  );

  if (!offer) {
    return (
      <div className="nl-page product-page">
        {crumbs}
        <h1 className="product-title">{data.name}</h1>
        <div className="product-empty">
          {data.served
            ? <EmptyState action={<SiteLink href={link('/shop')} className="btn btn-primary">{t('backToShop')}</SiteLink>}>{t('unavailable', { city: data.market })}</EmptyState>
            : <EmptyState action={<PlaceButton>{t('changeLocation')}</PlaceButton>}>{t('notServed', { city: data.market })}</EmptyState>}
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
  const { money } = useFormatters();
  const navigate = useNavigate();
  const add = useAddToCart();
  const firstInStock = offer.variants.find(v => v.stock > 0) ?? offer.variants[0];
  const [variantId, setVariantId] = useState(firstInStock?.variantId);
  const variant = offer.variants.find(v => v.variantId === variantId);
  const stock = variant ? variant.stock : offer.stock;
  const price = variant ? variant.priceCents : offer.priceCents;
  const plus = usePlusBadge(offer.offerId, price); // S-152: the shop's Plus member price
  const [qty, setQty] = useState(1);
  useEffect(() => { setQty(q => Math.max(1, Math.min(q, Math.max(1, stock)))); }, [stock]);
  const [image, setImage] = useState(0);
  const images = variant && variant.images.length ? variant.images : offer.images; // S-65: a variant's own photos
  useEffect(() => { setImage(0); }, [variantId]);
  const run = offer.runs[0];

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
  }, [stock, run, offer, data.direct, locale, zone]); // eslint-disable-line react-hooks/exhaustive-deps

  const item = { offerId: offer.offerId, variantId: variant?.variantId, qty };
  const onAdd = () => add.mutate(item, { onSuccess: () => void navigate({ to: '/cart' }) });
  const onBuy = () => add.mutate(item, { onSuccess: () => void navigate({ to: '/checkout' }) });
  const others = data.offers.filter(o => o.offerId !== offer.offerId);
  const shopHref = `/search?scope=shop&q=${encodeURIComponent(offer.shopName)}`;
  const stockLine = stock <= 0 ? <span className="product-stock product-stock-out">{t('outOfStock')}</span>
    : offer.lowStock || stock <= 3 ? <span className="product-stock product-stock-low">{t('lowStock', { count: stock })}</span>
      : <span className="product-stock product-stock-in">{t('inStock')}</span>;
  const returns = offer.returnsPolicy === 'standard_14' || offer.returnsPolicy === 'final_sale' ? t(`returns_${offer.returnsPolicy}`) : null;

  return (
    <div className="product-grid">
      <div className="product-thumbs" role="group" aria-label={t('gallery')}>
        {images.length > 1
          ? images.slice(0, 6).map((src, i) => (
            <button key={src} type="button" className="product-thumb" aria-label={t('showImage', { n: i + 1 })} aria-pressed={i === image} onClick={() => setImage(i)}><img src={src} alt="" /></button>))
          : null}
      </div>
      <div className="product-media">
        <div className="product-main">{images[image] ? <img src={images[image]} alt={data.name} /> : null}</div>
      </div>

      <div className="product-info">
        <h1 className="product-title">{data.name}</h1>
        <SiteLink href={shopHref} className="product-store">{t('visitStore', { shop: offer.shopName })}</SiteLink>
        {offer.ratingCount > 0 ? <Stars rating={offer.rating} reviewCount={offer.ratingCount} showValue /> : null}
        <hr className="product-rule" />
        <div className="product-price">
          <Price cents={price} compareAtCents={offer.compareAtCents} size="xl" />
          <span className="product-price-note">{data.unit ? t('priceNote', { unit: data.unit }) : t('priceNoteNoUnit')}</span>
          {plus ? <span className="tag tag-accent product-plus">{plus}</span> : null}
        </div>
        {run && stock > 0 ? <span className="tag tag-brand product-run">{t(`tag_${runWhen(run, zone)}`, { weekday: weekday(run.startsAt, locale, zone) })}</span> : null}

        {offer.variants.length > 0 ? (
          <fieldset className="product-options">
            <legend>{t('options')}</legend>
            <div className="nl-chips">
              {offer.variants.map(v => (
                <button key={v.variantId} type="button" className="nl-chip product-variant" aria-pressed={v.variantId === variantId} disabled={v.stock <= 0}
                  onClick={() => setVariantId(v.variantId)}>{v.value}</button>
              ))}
            </div>
          </fieldset>
        ) : null}

        <dl className="product-facts">
          {data.brand ? <><dt>{t('fact_brand')}</dt><dd>{data.brand}</dd></> : null}
          {data.unit ? <><dt>{t('fact_size')}</dt><dd>{data.unit}</dd></> : null}
          {data.departmentName ? <><dt>{t('fact_department')}</dt><dd>{data.departmentName}</dd></> : null}
          <dt>{t('fact_soldBy')}</dt><dd>{offer.shopName}</dd>
          {returns ? <><dt>{t('fact_returns')}</dt><dd>{returns}</dd></> : null}
        </dl>
        {data.description || data.bullets.length > 0 ? (
          <section aria-labelledby="product-about">
            <h2 id="product-about" className="product-h2">{t('about')}</h2>
            {data.description ? <p className="product-desc">{data.description}</p> : null}
            {data.bullets.length > 0 ? <ul className="product-bullets">{data.bullets.map(b => <li key={b}>{b}</li>)}</ul> : null}
          </section>
        ) : null}
      </div>

      <aside className="product-buy" aria-label={t('buyBox')}>
        <Price cents={price} size="lg" />
        <p className="product-delivery">{delivery}</p>
        {stockLine}
        {data.age ? (
          <Alert tone="info" title={t('ageTitle')}>
            {data.age.minimumAge ? t('ageBody', { age: data.age.minimumAge }) : t('ageBodyNoAge')}
          </Alert>
        ) : null}
        <div className="product-qty">
          <span className="product-qty-label" aria-hidden>{t('qty')}</span>
          <QuantityStepper value={qty} max={Math.max(1, Math.min(99, stock))} onChange={setQty} name={data.name} removable={false} />
        </div>
        <button type="button" className="btn btn-accent btn-block" disabled={stock <= 0 || add.isPending} aria-busy={add.isPending} onClick={onAdd}>
          {add.isPending ? t('adding') : t('addToCart')}
        </button>
        <button type="button" className="btn btn-primary btn-block" disabled={stock <= 0 || add.isPending} onClick={onBuy}>{t('buyNow')}</button>
        {add.isError ? <Alert tone="error" role="alert">{t('addFailed')}</Alert> : null}
        <dl className="product-buy-facts">
          <dt>{t('fact_soldBy')}</dt><dd><SiteLink href={shopHref}>{offer.shopName}</SiteLink></dd>
          {returns ? <><dt>{t('fact_returns')}</dt><dd>{returns}</dd></> : null}
          <dt>{t('fact_payment')}</dt><dd>{t('escrow')}</dd>
        </dl>

        {others.length > 0 ? (
          <section aria-labelledby="product-sellers" className="product-sellers">
            <h2 id="product-sellers" className="product-h3">{t('sellers')}</h2>
            <ul>
              {others.map(o => {
                const r = o.runs[0];
                const when = o.stock <= 0 ? t('outOfStock') : r ? t(`tag_${runWhen(r, zone)}`, { weekday: weekday(r.startsAt, locale, zone) }) : t('notOnRunShort');
                return (
                  <li key={o.offerId} className="product-seller">
                    <span className={`product-seller-mark nl-swatch-${swatchOf(o.merchantId)}`} aria-hidden>{o.shopName.charAt(0).toUpperCase()}</span>
                    <span className="product-seller-body">
                      <strong>{o.shopName}</strong>
                      <span className="product-seller-meta">{when}</span>
                      <span className="product-seller-price">{money(o.priceCents)}</span>
                    </span>
                    <SiteLink href={link(`/products/${data.productId}?offer=${o.offerId}`)} className="btn btn-secondary btn-sm" aria-label={`${t('choose')} — ${o.shopName}`}>{t('choose')}</SiteLink>
                  </li>
                );
              })}
            </ul>
          </section>
        ) : null}
      </aside>

      {offer.more.length > 0 ? (
        <div className="product-more-section">
          <RailSection id="product-more" title={t('alsoFrom', { shop: offer.shopName })}>
            <RailTrack size="tile">
              {offer.more.map(m => (
                <li key={m.productId}>
                  <SiteLink href={link(`/products/${m.productId}`)} className="nl-tile product-more-card" aria-label={t('moreItem', { name: m.name, price: money(m.priceCents) })}>
                    <span className="nl-tile-pic product-more-pic"><Scene slot={[departmentSlot(data.departmentSlug)]} subject={`${m.name} ${data.departmentSlug ?? ''}`} crop="square" sizes="180px" /></span>
                    <span className="nl-tile-name">{m.name}</span>
                    <span className="product-more-price">{money(m.priceCents)}</span>
                  </SiteLink>
                </li>
              ))}
            </RailTrack>
          </RailSection>
        </div>
      ) : null}

      {/* phones: the main action within thumb reach (the buy box is far below the photo and the details) */}
      <div className="nl-stickybar product-stickybar">
        <button type="button" className="btn btn-accent btn-lg" disabled={stock <= 0 || add.isPending} onClick={onAdd}>
          {t('addWithPrice', { price: money(price) })}
        </button>
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
        <div className="product-thumbs" />
        <div className="product-media"><Skeleton height={0} radius={8} style={{ aspectRatio: '1 / 1', height: 'auto' }} /></div>
        <div className="product-info">
          <Skeleton width="70%" height={28} /><Skeleton width="40%" height={14} style={{ marginTop: 10 }} />
          <Skeleton width={120} height={36} style={{ marginTop: 22 }} /><Skeleton height={80} style={{ marginTop: 16 }} />
        </div>
        <Skeleton height={320} radius={8} />
      </div>
    </div>
  );
}
