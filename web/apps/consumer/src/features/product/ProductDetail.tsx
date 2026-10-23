import { useEffect, useState, type ReactNode } from 'react';
import { useZone } from '../location/regions';
import { useSuspenseQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Check, Lock, Receipt, Storefront, Truck, Warning } from '@phosphor-icons/react';
import { Alert, EmptyState, Price, QuantityStepper, Segmented, SiteLink, Skeleton, Stars, swatchOf, unitPriceOf, useFormatters, useLocale } from '@northline/ui';
import { clock, runWhen, weekday, windowRange } from '../shop/format';
import { useMarketFollowsLocation, withMarket } from '../shop/market';
import { minutesUntil, useNow } from '../shop/run';
import { productQuery, useAddToCart, type Offer, type ProductPage } from './api';
import { Crumbs } from '../pages/Crumbs';
import { RailSection, RailTrack } from '../pages/Rail';
import { PhotoWell } from '../art/PhotoWell';
import { PlaceButton } from '../pages/PlaceButton';
import { OfferCard } from '../search/ItemCard';
import { storeHref } from '../shop/store';
import { useProductT } from './messages';
import { usePlusBadge } from '../loyalty/PlusBadge';

/**
 * Product detail (E-15 S-172, design/revamp/a.html "Product page with buy box"; S-50 behaviour): breadcrumb · a
 * thumbnail column and the photo well · the details (store link, title, rating, badges, the variant as joined boxes,
 * about, the spec table) · a bordered buy box (price with unit price, the GST note, the delivery window with the
 * order-within countdown, stock, quantity, Add to cart, Buy now, sold-by / payment / returns, other sellers) · "More from
 * <shop>" · the seller's rating. On phones the buy box's action is a sticky bottom bar.
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
      ...(offer ? [{ label: offer.shopName, href: storeHref(offer.merchantId, data.departmentSlug, explicit) }] : []),
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
      <OfferView key={offer.offerId} data={data} offer={offer} link={link} market={explicit} />
    </div>
  );
}

const THEMES = new Set(['size', 'colour', 'length', 'flavour', 'weight']);

function OfferView({ data, offer, link, market }: { data: ProductPage; offer: Offer; link: (p: string) => string; market?: string }) {
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
  const run = stock > 0 ? offer.runs[0] : undefined;
  const second = stock > 0 ? offer.runs[1] : undefined;
  // the unit price from the pack's own words (the size the variant names, the record's unit, the title); never a guess
  const unit = unitPriceOf(price, variant?.value, data.unit, data.name);

  const item = { offerId: offer.offerId, variantId: variant?.variantId, qty };
  const onAdd = () => add.mutate(item, { onSuccess: () => void navigate({ to: '/cart' }) });
  const onBuy = () => add.mutate(item, { onSuccess: () => void navigate({ to: '/checkout' }) });
  const others = data.offers.filter(o => o.offerId !== offer.offerId);
  const store = storeHref(offer.merchantId, data.departmentSlug, market);
  const returns = offer.returnsPolicy === 'standard_14' || offer.returnsPolicy === 'final_sale' ? t(`returns_${offer.returnsPolicy}`) : null;
  const condition = offer.condition === 'new' || offer.condition === 'used' || offer.condition === 'refurbished' ? t(`condition_${offer.condition}`) : null;
  const when = run ? runWhen(run, zone) : null;
  const windowText = (r: Offer['runs'][number]) => t(`win_${runWhen(r, zone)}`, { range: windowRange(r.startsAt, r.endsAt, locale, zone), weekday: weekday(r.startsAt, locale, zone) });
  const theme = THEMES.has(offer.variantTheme) ? t(`theme_${offer.variantTheme}` as 'theme_size') : t('options');
  const disabled = stock <= 0 || add.isPending;

  const specs: [string, ReactNode][] = [
    ...(data.brand ? [[t('fact_brand'), data.brand] as [string, ReactNode]] : []),
    ...(data.unit ? [[t('fact_size'), data.unit] as [string, ReactNode]] : []),
    ...(data.departmentName ? [[t('fact_department'), data.departmentName] as [string, ReactNode]] : []),
    ...(condition ? [[t('fact_condition'), condition] as [string, ReactNode]] : []),
    [t('fact_soldBy'), offer.shopName],
    ...(returns ? [[t('fact_returns'), returns] as [string, ReactNode]] : []),
  ];

  return (
    <div className="product-grid">
      <div className="product-thumbs" role="group" aria-label={t('gallery')}>
        {images.length > 1
          ? images.slice(0, 6).map((src, i) => (
            <button key={src} type="button" className="product-thumb" aria-label={t('showImage', { n: i + 1 })} aria-pressed={i === image} onClick={() => setImage(i)}><img src={src} alt="" /></button>))
          : null}
      </div>
      <div className="product-media">
        <div className="product-main">
          {images[image] ? <img src={images[image]} alt={data.name} /> : <PhotoWell subject={`${data.departmentSlug} ${data.name}`} />}
          {images.length > 1 ? <span className="product-count" aria-hidden>{image + 1} / {images.length}</span> : null}
        </div>
      </div>

      <div className="product-info">
        <SiteLink href={store} className="product-store"><Storefront size={16} aria-hidden />{t('visitStore', { shop: offer.shopName })}</SiteLink>
        <h1 className="product-title">{data.name}</h1>
        {offer.ratingCount > 0 ? <Stars rating={offer.rating} reviewCount={offer.ratingCount} showValue /> : null}
        {(run && when) || plus ? (
          <div className="product-badges">
            {run && when ? <span className="tag tag-neutral product-run">{t(`tag_${when}`, { weekday: weekday(run.startsAt, locale, zone) })}</span> : null}
            {plus ? <span className="tag tag-accent product-plus">{plus}</span> : null}
          </div>
        ) : null}
        <hr className="product-rule" />
        <div className="product-price">
          <Price cents={price} compareAtCents={offer.compareAtCents} unit={unit} size="xl" />
          <span className="product-price-note">{data.unit ? t('priceNote', { unit: data.unit }) : t('priceNoteNoUnit')}</span>
        </div>

        {offer.variants.length > 0 ? (
          <div className="product-options" role="group" aria-labelledby="product-variant-label">
            <div id="product-variant-label" className="product-options-label">{theme}{variant ? <>: <strong>{variant.value}</strong></> : null}</div>
            <Segmented name={`variant-${offer.offerId}`} aria-label={theme} value={variantId ?? ''} onChange={setVariantId}
              options={offer.variants.map(v => ({ value: v.variantId, label: v.stock > 0 ? t('variantOption', { value: v.value, price: money(v.priceCents) }) : t('variantSoldOut', { value: v.value }) }))} />
          </div>
        ) : null}

        {data.description || data.bullets.length > 0 ? (
          <section aria-labelledby="product-about" className="product-about">
            <h2 id="product-about" className="product-h2">{t('about')}</h2>
            {data.description ? <p className="product-desc">{data.description}</p> : null}
            {data.bullets.length > 0 ? <ul className="product-bullets">{data.bullets.map(b => <li key={b}>{b}</li>)}</ul> : null}
          </section>
        ) : null}
        <table className="product-specs">
          <caption className="nl-sr-only">{t('specs')}</caption>
          <tbody>{specs.map(([k, v]) => <tr key={k}><th scope="row">{k}</th><td>{v}</td></tr>)}</tbody>
        </table>
      </div>

      <aside className="product-buy" aria-label={t('buyBox')}>
        <Price cents={price} unit={unit} size="lg" />
        <div className="product-buy-row">
          <Truck size={18} aria-hidden className="product-buy-icon" />
          <div className="product-buy-text">
            {stock <= 0 ? <strong>{t('deliveryOut', { shop: offer.shopName })}</strong>
              : run ? (
                <>
                  <strong>{t('deliveryWindow', { window: windowText(run) })}</strong>
                  <span className="product-buy-meta">{t('pooled', { fee: run.feeCents === 0 ? t('free') : money(run.feeCents) })}{second ? ` · ${t('orLater', { window: windowText(second) })}` : ''}</span>
                  <OrderWithin orderBy={run.orderBy} by={clock(run.orderBy, locale, zone)} />
                </>
              ) : <strong>{t('notOnRun')}</strong>}
            {stock > 0 && data.direct ? <span className="product-buy-meta">{t('direct', { eta: data.direct.etaMinutes, fee: money(data.direct.feeCents) })}</span> : null}
          </div>
        </div>
        {stock <= 0 ? <span className="product-stock product-stock-out">{t('outOfStock')}</span>
          : offer.lowStock || stock <= 3 ? <span className="product-stock product-stock-low"><Warning size={16} aria-hidden />{t('lowStock', { count: stock })}</span>
            : <span className="product-stock product-stock-in"><Check size={16} weight="bold" aria-hidden />{t('inStock')}</span>}
        {data.age ? (
          <Alert tone="info" title={t('ageTitle')}>
            {data.age.minimumAge ? t('ageBody', { age: data.age.minimumAge }) : t('ageBodyNoAge')}
          </Alert>
        ) : null}
        <div className="product-qty">
          <span className="product-qty-label" aria-hidden>{t('qty')}</span>
          <QuantityStepper value={qty} max={Math.max(1, Math.min(99, stock))} onChange={setQty} name={data.name} removable={false} />
        </div>
        <button type="button" className="btn btn-primary btn-lg btn-block" disabled={disabled} aria-busy={add.isPending} onClick={onAdd}>
          {add.isPending ? t('adding') : t('addToCart')}
        </button>
        <button type="button" className="btn btn-secondary btn-lg btn-block" disabled={disabled} onClick={onBuy}>{t('buyNow')}</button>
        {add.isError ? <Alert tone="error" role="alert">{t('addFailed')}</Alert> : null}
        <dl className="product-buy-facts">
          <div><dt>{t('fact_soldBy')}</dt><dd><SiteLink href={store}>{offer.shopName}</SiteLink></dd></div>
          <div><dt>{t('fact_payment')}</dt><dd><Lock size={14} aria-hidden className="product-fact-icon" />{t('escrow')}</dd></div>
          {returns ? <div><dt>{t('fact_returns')}</dt><dd><Receipt size={14} aria-hidden className="product-fact-icon" />{returns}</dd></div> : null}
        </dl>

        {others.length > 0 ? (
          <section aria-labelledby="product-sellers" className="product-sellers">
            <h2 id="product-sellers" className="product-h3">{t('sellers')}</h2>
            <ul>
              {others.map(o => {
                const r = o.runs[0];
                const at = o.stock <= 0 ? t('outOfStock') : r ? t(`tag_${runWhen(r, zone)}`, { weekday: weekday(r.startsAt, locale, zone) }) : t('notOnRunShort');
                return (
                  <li key={o.offerId} className="product-seller">
                    <span className={`product-seller-mark nl-swatch-${swatchOf(o.merchantId)}`} aria-hidden>{o.shopName.charAt(0).toUpperCase()}</span>
                    <span className="product-seller-body">
                      <strong>{o.shopName}</strong>
                      <span className="product-seller-meta">{at}</span>
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
        <div className="product-wide">
          <RailSection id="product-more" title={t('alsoFrom', { shop: offer.shopName })} more={{ href: store, label: t('seeStore') }}>
            <RailTrack size="product">
              {offer.more.map(m => (
                <li key={m.productId}>
                  <OfferCard offerId={null} href={link(`/products/${m.productId}`)} name={m.name} image={null} variant="rail"
                    fallback={<PhotoWell subject={`${data.departmentSlug} ${m.name}`} />} priceCents={m.priceCents} meta={offer.shopName} />
                </li>
              ))}
            </RailTrack>
          </RailSection>
        </div>
      ) : null}

      {offer.ratingCount > 0 ? (
        <section className="product-wide product-reviews" aria-labelledby="product-reviews">
          <h2 id="product-reviews" className="product-section-title">{t('reviews')}</h2>
          <div className="product-reviews-sum">
            <Stars rating={offer.rating} size={18} />
            <p className="product-reviews-line">{t('reviewsLine', { rating: offer.rating.toLocaleString(locale === 'fr' ? 'fr-CA' : 'en-CA', { minimumFractionDigits: 1, maximumFractionDigits: 1 }), count: offer.ratingCount, shop: offer.shopName })}</p>
            <SiteLink href={store} className="product-reviews-link">{t('seeStore')}</SiteLink>
          </div>
        </section>
      ) : null}

      {/* phones: the buy box's price and action within thumb reach (the buy box is far below the photo and the details) */}
      <div className="nl-stickybar product-stickybar">
        <span className="nl-stickybar-sum"><strong>{money(price)}</strong>{run ? <span>{windowText(run)}</span> : null}</span>
        <button type="button" className="btn btn-primary btn-lg" disabled={disabled} onClick={onAdd}>{t('addToCart')}</button>
      </div>
    </div>
  );
}

/**
 * "Order within 2 h 48 min" — the time left before the run closes. The server and the browser's first render say "Order
 * by 5:20 p.m." (a clock read at render would differ between them); the browser then counts down.
 */
function OrderWithin({ orderBy, by }: { orderBy: string; by: string }) {
  const t = useProductT();
  const now = useNow();
  const left = now === null ? null : minutesUntil(orderBy, now);
  if (left !== null && left <= 0) return null;
  return <span className="product-buy-cutoff">{left === null ? t('orderBy', { time: by }) : t('orderWithin', { hours: Math.floor(left / 60), minutes: left % 60 })}</span>;
}

export function ProductSkeleton() {
  const t = useProductT();
  return (
    <div className="nl-page product-page" aria-busy="true">
      <span className="nl-sr-only">{t('loading')}</span>
      <Skeleton width={260} height={12} />
      <div className="product-grid">
        <div className="product-thumbs" />
        <div className="product-media"><span className="product-main product-main-skeleton" /></div>
        <div className="product-info">
          <Skeleton width="70%" height={28} /><Skeleton width="40%" height={14} />
          <Skeleton width={120} height={36} /><Skeleton height={80} />
        </div>
        <Skeleton height={320} radius="var(--radius-panel)" />
      </div>
    </div>
  );
}
