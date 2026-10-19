import { useQuery } from '@tanstack/react-query';
import { EmptyState, ErrorState, Price, QuantityStepper, SiteLink, Skeleton, useFormatters } from '@northline/ui';
import { signInHref, useViewer } from '../session/api';
import { cartQuery, useChangeCartLine, type Cart, type CartLine } from './api';
import { Art } from '../art/Art';
import { useCartT } from './messages';

/**
 * The cart (redesign § 1.3 "Cart", AT Shop:425–500; S-51 behaviour): the lines grouped by shop — image, name, option,
 * stock, a quantity stepper and Delete — with "Subtotal (N items)" under them, beside a 300px sticky aside with the
 * subtotal and "Proceed to checkout". Payment left the cart (§ 3.8): the 3-step checkout is /checkout. Guests see their
 * cart and sign in on the way to the checkout.
 */
export function CartPage() {
  const t = useCartT();
  const { user, loading } = useViewer();
  const cart = useQuery(cartQuery);
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
      </div>
    );
  }
  const view = cart.data;
  const blocked = view.groups.some(g => g.items.some(i => !i.available));
  return (
    <div className="nl-page cart-page">
      <div className="cart-grid">
        <div className="cart-main">
          <div className="cart-head">
            <h1 className="cart-title">{t('cartTitle')}</h1>
            <span className="cart-sub">{t('sub', { items: view.itemCount, shops: view.shopCount })}</span>
          </div>
          <CartGroups cart={view} />
          <p className="cart-subtotal">{t('subtotalItems', { count: view.itemCount })} <strong><Price cents={view.subtotalCents} size="sm" /></strong></p>
        </div>
        <aside className="cart-summary" aria-label={t('summary')}>
          <p className="cart-subtotal cart-subtotal-aside">{t('subtotalItems', { count: view.itemCount })} <strong><Price cents={view.subtotalCents} size="md" /></strong></p>
          <p className="cart-note">{t('taxAtCheckout')}</p>
          {blocked ? <p className="cart-note cart-note-warn" role="note">{t('fixLines')}</p> : null}
          {user
            ? <SiteLink href="/checkout" className="btn btn-primary btn-block cart-pay" aria-disabled={blocked || undefined} onClick={e => { if (blocked) e.preventDefault(); }}>{t('proceed')}</SiteLink>
            : <SiteLink href={signInHref('/checkout')} className="btn btn-primary btn-block cart-pay">{t('signInToCheckout')}</SiteLink>}
          <p className="cart-note">{t('payNote')}</p>
        </aside>
      </div>
    </div>
  );
}

/** The cart's lines by shop (the checkout's review step shows the same groups, read-only). */
export function CartGroups({ cart, readOnly }: { cart: Cart; readOnly?: boolean }) {
  const t = useCartT();
  return (
    <>
      {cart.groups.map(g => (
        <section key={g.merchantId || 'gone'} className="cart-group" aria-label={g.shopName || t('unavailableGroup')}>
          <h2 className="cart-shop">{g.shopName || t('unavailableGroup')}</h2>
          <ul className="cart-lines">{g.items.map(item => <CartItem key={item.itemId} item={item} readOnly={readOnly} />)}</ul>
        </section>
      ))}
    </>
  );
}

function CartItem({ item, readOnly }: { item: CartLine; readOnly?: boolean }) {
  const t = useCartT();
  const { money } = useFormatters();
  const change = useChangeCartLine();
  const name = item.name || '—';
  const meta = item.option && item.unit ? t('itemMeta', { option: item.option, unit: item.unit }) : item.option ?? item.unit ?? '';
  const problem = !item.available ? (item.stock > 0 && item.name ? t('notEnough', { count: item.stock }) : t('unavailable')) : null;
  return (
    <li className="cart-line">
      <span className="cart-line-media" aria-hidden>{item.imageUrl ? <img src={item.imageUrl} alt="" /> : <Art subject={item.name} />}</span>
      <span className="cart-line-body">
        {item.productId ? <SiteLink href={`/products/${item.productId}`} className="cart-line-name">{name}</SiteLink> : <span className="cart-line-name">{name}</span>}
        {meta ? <span className="cart-line-meta">{meta}</span> : null}
        {problem ? <span className="cart-line-problem" role="note">{problem}</span> : item.stock <= 3 ? <span className="cart-line-low">{t('onlyLeft', { count: item.stock })}</span> : <span className="cart-line-stock">{t('inStock')}</span>}
        {readOnly ? <span className="cart-line-meta">{t('qtyValue', { qty: item.qty })}</span> : (
          <span className="cart-line-actions">
            <QuantityStepper value={item.qty} max={Math.min(99, Math.max(item.qty, item.stock))} name={name} busy={change.isPending}
              onChange={qty => change.mutate({ itemId: item.itemId, qty })} />
            <span className="cart-line-pipe" aria-hidden>|</span>
            <button type="button" className="btn btn-link cart-line-delete" disabled={change.isPending} aria-label={t('deleteNamed', { name })}
              onClick={() => change.mutate({ itemId: item.itemId, qty: 0 })}>{t('delete')}</button>
          </span>
        )}
      </span>
      <span className="cart-line-total">{money(item.lineCents)}</span>
    </li>
  );
}

export function CartSkeleton() {
  const t = useCartT();
  return (
    <div className="nl-page cart-page" aria-busy="true">
      <span className="nl-sr-only">{t('loading')}</span>
      <div className="cart-grid">
        <div>
          <h1 className="cart-title">{t('cartTitle')}</h1><Skeleton width={260} height={14} style={{ marginTop: 10 }} />
          {Array.from({ length: 3 }, (_, i) => <Skeleton key={i} height={110} style={{ marginTop: 18 }} />)}
        </div>
        <Skeleton height={200} radius={8} />
      </div>
    </div>
  );
}
