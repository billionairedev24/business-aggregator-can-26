import { useQuery } from '@tanstack/react-query';
import { Truck } from '@phosphor-icons/react';
import { Alert, EmptyState, ErrorState, Price, QuantityStepper, SiteLink, Skeleton, useFormatters, useLocale } from '@northline/ui';
import { signInHref, useViewer } from '../session/api';
import { useZone } from '../location/regions';
import { clock, runWhen, weekday, windowRange } from '../shop/format';
import { useNextRun } from '../shop/run';
import { cartQuery, useChangeCartLine, type Cart, type CartLine } from './api';
import { PhotoWell } from '../art/PhotoWell';
import { useSavedForLater, type SavedLine } from './saved';
import { useCartT } from './messages';

type Saved = ReturnType<typeof useSavedForLater>;

/**
 * The cart (E-15, in the checkout's system — design/revamp/a.html draws no cart of its own): the lines grouped by shop
 * in bordered 4 px panels — the photo well, the name, option and unit, stock, a quantity stepper, Delete and Save for
 * later — under the pooled-run note (the shops in the cart ride one run: one delivery fee), beside the subtotal box
 * with "Proceed to checkout". "Saved for later" follows the cart (kept in this browser, ./saved.ts). Guests see their
 * cart and sign in on the way to the checkout.
 */
export function CartPage() {
  const t = useCartT();
  const { user, loading } = useViewer();
  const cart = useQuery(cartQuery);
  const later = useSavedForLater();
  if (cart.isPending || loading) return <CartSkeleton />;
  // S-142: the loading and error states keep the page's h1
  if (cart.isError) return <div className="nl-page cart-page"><h1 className="cart-title">{t('cartTitle')}</h1><ErrorState message={t('loadError')} onRetry={() => void cart.refetch()} /></div>;
  if (cart.data.itemCount === 0) {
    return (
      <div className="nl-page cart-page">
        <h1 className="cart-title">{t('cartTitle')}</h1>
        <div className="cart-empty">
          <EmptyState title={t('empty')} action={<SiteLink href="/shop" className="btn btn-primary">{t('continueShopping')}</SiteLink>}>{t('emptyBody')}</EmptyState>
        </div>
        <SavedList later={later} />
      </div>
    );
  }
  const view = cart.data;
  return (
    <div className="nl-page cart-page">
      <div className="cart-grid">
        <div className="cart-main">
          <div className="cart-head">
            <h1 className="cart-title">{t('cartTitle')}</h1>
            <span className="cart-sub">{t('sub', { items: view.itemCount, shops: view.shopCount })}</span>
          </div>
          <RunNote shops={view.shopCount} />
          {later.failed ? <Alert tone="error" role="alert">{t('savedFailed')}</Alert> : null}
          <CartGroups cart={view} later={later} />
          <p className="cart-subtotal">{t('subtotalItems', { count: view.itemCount })} <strong><Price cents={view.subtotalCents} size="md" /></strong></p>
          <SavedList later={later} />
        </div>
        <SubtotalBox cart={view} signedIn={!!user} />
      </div>
    </div>
  );
}

function SubtotalBox({ cart: view, signedIn }: { cart: Cart; signedIn: boolean }) {
  const t = useCartT();
  const { price } = useFormatters();
  const blocked = view.groups.some(g => g.items.some(i => !i.available));
  return (
    <aside className="cart-summary" aria-label={t('summary')}>
      <p className="cart-subtotal cart-subtotal-aside">{t('subtotalItems', { count: view.itemCount })} <strong><Price cents={view.subtotalCents} size="lg" /></strong></p>
      {view.plusSavingsCents > 0 ? <p className="cart-note cart-plus" role="note">{t('plusSaved', { amount: price(view.plusSavingsCents) })}</p> : null}
      <p className="cart-note">{t('taxAtCheckout')}</p>
      {blocked ? <p className="cart-note cart-note-warn" role="note">{t('fixLines')}</p> : null}
      {signedIn
        ? <SiteLink href="/checkout" className="btn btn-primary btn-block cart-pay" aria-disabled={blocked || undefined} onClick={e => { if (blocked) e.preventDefault(); }}>{t('proceed')}</SiteLink>
        : <SiteLink href={signInHref('/checkout')} className="btn btn-primary btn-block cart-pay">{t('signInToCheckout')}</SiteLink>}
      <p className="cart-note">{t('payNote')}</p>
    </aside>
  );
}

/**
 * "Pooled run" (the cut-off strip's run, S-170): every shop in the cart rides the next run for one delivery fee, the
 * order-by time named. Nothing while the run loads or where there is none (the checkout lists every window).
 */
function RunNote({ shops }: { shops: number }) {
  const t = useCartT();
  const { locale } = useLocale();
  const { price } = useFormatters();
  const { run, market } = useNextRun();
  const zone = useZone(market);
  if (!run) return null;
  const when = t(`win_${runWhen(run, zone)}`, { range: windowRange(run.startsAt, run.endsAt, locale, zone), weekday: weekday(run.startsAt, locale, zone) });
  return (
    <p className="cart-run" role="note">
      <Truck size={18} aria-hidden />
      <span><strong>{t('runTitle', { when })}</strong> {t('runBody', { count: shops, fee: price(run.feeCents), time: clock(run.orderBy, locale, zone) })}</span>
    </p>
  );
}

/** The cart's lines by shop, each shop a bordered panel. */
export function CartGroups({ cart, later }: { cart: Cart; later?: Saved }) {
  const t = useCartT();
  return (
    <>
      {cart.groups.map(g => (
        <section key={g.merchantId || 'gone'} className="cart-group" aria-label={g.shopName || t('unavailableGroup')}>
          <h2 className="cart-shop">{g.shopName || t('unavailableGroup')}<span className="cart-shop-count">{t('shopItems', { count: g.items.reduce((n, i) => n + i.qty, 0) })}</span></h2>
          <ul className="cart-lines">{g.items.map(item => <CartItem key={item.itemId} item={item} shopName={g.shopName} later={later} />)}</ul>
        </section>
      ))}
    </>
  );
}

function CartItem({ item, shopName, later }: { item: CartLine; shopName: string; later?: Saved }) {
  const t = useCartT();
  const { price } = useFormatters();
  const change = useChangeCartLine();
  const name = item.name || '—';
  const meta = item.option && item.unit ? t('itemMeta', { option: item.option, unit: item.unit }) : item.option ?? item.unit ?? '';
  const problem = !item.available ? (item.stock > 0 && item.name ? t('notEnough', { count: item.stock }) : t('unavailable')) : null;
  const busy = change.isPending || !!later?.busy;
  return (
    <li className="cart-line">
      <span className="cart-line-media" aria-hidden>{item.imageUrl ? <img src={item.imageUrl} alt="" /> : <PhotoWell subject={item.name} />}</span>
      <span className="cart-line-body">
        {item.productId ? <SiteLink href={`/products/${item.productId}`} className="cart-line-name">{name}</SiteLink> : <span className="cart-line-name">{name}</span>}
        {meta ? <span className="cart-line-meta">{meta}</span> : null}
        {item.memberPrice && item.listUnitCents != null
          ? <span className="cart-line-plus">{t('plusPriceLine', { price: price(item.unitCents), was: price(item.listUnitCents) })}</span>
          : item.memberUnitCents != null ? <span className="cart-line-plus">{t('plusOffer', { price: price(item.memberUnitCents) })}</span> : null}
        {problem ? <span className="cart-line-problem" role="note">{problem}</span> : item.stock <= 3 ? <span className="cart-line-low">{t('onlyLeft', { count: item.stock })}</span> : <span className="cart-line-stock">{t('inStock')}</span>}
        <span className="cart-line-actions">
          <QuantityStepper value={item.qty} max={Math.min(99, Math.max(item.qty, item.stock))} name={name} busy={busy}
            onChange={qty => change.mutate({ itemId: item.itemId, qty })} />
          <span className="cart-line-pipe" aria-hidden>|</span>
          <button type="button" className="btn btn-link cart-line-action" disabled={busy} aria-label={t('deleteNamed', { name })}
            onClick={() => change.mutate({ itemId: item.itemId, qty: 0 })}>{t('delete')}</button>
          {later && item.offerId ? (
            <>
              <span className="cart-line-pipe" aria-hidden>|</span>
              <button type="button" className="btn btn-link cart-line-action" disabled={busy} aria-label={t('saveNamed', { name })}
                onClick={() => void later.save(item, shopName)}>{t('save')}</button>
            </>
          ) : null}
        </span>
      </span>
      <span className="cart-line-total"><Price cents={item.lineCents} size="md" /></span>
    </li>
  );
}

/** "Saved for later (n)": each with Move to cart and Delete; nothing until the browser has read the list. */
function SavedList({ later }: { later: Saved }) {
  const t = useCartT();
  const list = later.saved;
  if (!list || list.length === 0) return null;
  return (
    <section className="cart-group cart-saved" aria-labelledby="cart-saved">
      <h2 id="cart-saved" className="cart-shop">{t('savedTitle', { count: list.length })}</h2>
      <ul className="cart-lines">{list.map(l => <SavedItem key={`${l.offerId}:${l.variantId ?? ''}`} entry={l} later={later} />)}</ul>
    </section>
  );
}

function SavedItem({ entry: l, later }: { entry: SavedLine; later: Saved }) {
  const t = useCartT();
  const meta = [l.option, l.unit, l.shopName].filter(Boolean).join(' · ');
  return (
    <li className="cart-line">
      <span className="cart-line-media" aria-hidden>{l.imageUrl ? <img src={l.imageUrl} alt="" /> : <PhotoWell subject={l.name} />}</span>
      <span className="cart-line-body">
        <SiteLink href={`/products/${l.productId}`} className="cart-line-name">{l.name}</SiteLink>
        {meta ? <span className="cart-line-meta">{meta}</span> : null}
        <span className="cart-line-actions">
          <button type="button" className="btn btn-secondary btn-sm" disabled={later.busy} aria-label={t('moveNamed', { name: l.name })} onClick={() => void later.moveToCart(l)}>{t('moveToCart')}</button>
          <button type="button" className="btn btn-link cart-line-action" disabled={later.busy} aria-label={t('deleteNamed', { name: l.name })} onClick={() => later.remove(l)}>{t('delete')}</button>
        </span>
      </span>
      <span className="cart-line-total"><Price cents={l.unitCents} size="md" /></span>
    </li>
  );
}

export function CartSkeleton() {
  const t = useCartT();
  return (
    <div className="nl-page cart-page" aria-busy="true">
      <span className="nl-sr-only">{t('loading')}</span>
      <div className="cart-grid">
        <div className="cart-main cart-skeleton">
          <h1 className="cart-title">{t('cartTitle')}</h1>
          {Array.from({ length: 3 }, (_, i) => <Skeleton key={i} height={110} />)}
        </div>
        <Skeleton height={200} />
      </div>
    </div>
  );
}
