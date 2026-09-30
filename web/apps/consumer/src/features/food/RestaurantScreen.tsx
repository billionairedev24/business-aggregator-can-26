import { useState } from 'react';
import { Link, useNavigate } from '@tanstack/react-router';
import { useSuspenseQuery } from '@tanstack/react-query';
import { Alert, Button, Dialog, Field, Tag, TextInput, useFormatters, useLocale } from '@northline/ui';
import { restaurantQuery, type Combo, type Dish, type Restaurant } from './api';
import { subtotalOf, useFoodCart, type FoodCart } from './foodCart';
import { CUISINE_NAMES, containsLabel, TAGS, useFoodT } from './messages';
import { choiceNames, defaults, deltaOf, pickProblems, toggle, visibleGroups } from './modifiers';

type T = ReturnType<typeof useFoodT>;
/** Alberta's GST for the aside's estimate; checkout prices the tax on the server for the delivery address. */
const GST_BPS = 500;

/**
 * Restaurant (design 06 `restaurant`, S-57): the kitchen's banner, its live menus by section (combos first), a dish's
 * modifier groups with their pick rules, special instructions and quantity, and "Your order" — the food cart, one
 * kitchen at a time, with the $15 minimum. Server-rendered (SEO); the order lives in the browser.
 */
export function RestaurantScreen({ slug }: { slug: string }) {
  const t = useFoodT();
  const { locale } = useLocale();
  const { money, date } = useFormatters();
  const { data } = useSuspenseQuery(restaurantQuery(slug));
  const { cart, add, remove, clear } = useFoodCart();
  const [openId, setOpenId] = useState<string | null>(null);
  const [pending, setPending] = useState<null | (() => void)>(null);
  const k = data.kitchen;
  const kitchenRef = { merchantId: k.merchantId, slug, name: k.name };
  const cuisine = k.cuisines.map(c => CUISINE_NAMES[c]?.[locale] ?? c).join(', ');

  /** Adding from another kitchen asks first (a food order comes from one kitchen). */
  const guarded = (run: () => void) => {
    if (cart && cart.merchantId !== k.merchantId && cart.lines.length > 0) setPending(() => run);
    else run();
  };

  const orderable = k.open || data.slots.length > 0;
  const sections = [
    ...(data.combos.length ? [{ id: 'combos', name: t('combos') }] : []),
    ...data.sections.map(s => ({ id: s.id, name: s.name })),
  ];

  return (
    <div className="nl-rest">
      <nav className="nl-rest-crumbs" aria-label={t('breadcrumb')}>
        <Link to="/">{t('home')}</Link> › <Link to="/food">{t('title')}</Link>{cuisine ? <> › <span>{cuisine}</span></> : null} › <span aria-current="page">{k.name}</span>
      </nav>
      <header className="nl-rest-banner" style={k.brandColor ? { background: k.brandColor } : undefined}>
        <div>
          <h1>{k.name}</h1>
          <p>{[cuisine, data.address, k.reviews > 0 ? t('rating', { rating: k.rating.toFixed(1), count: k.reviews }) : null].filter(Boolean).join(' · ')}</p>
        </div>
        <div className="nl-rest-badges">
          <span className="tag">{t('etaRange', { from: k.etaFromMin, to: k.etaToMin })} · {money(k.deliveryFeeCents)}</span>
          {data.ahsVerified && <span className="tag">{t('ahs')}</span>}
          <span className="tag">{t('minOrder', { amount: money(data.minOrderCents, { whole: true }) })}</span>
        </div>
      </header>
      {!k.open && (
        <div className="nl-rest-alert">
          <Alert tone="neutral" role="status">
            {k.paused ? t('pausedBanner', { name: k.name }) : t('closedBanner', { name: k.name, when: k.opensAt ? t('closedOpens', { time: date(k.opensAt, 'time') }) : '' })}
          </Alert>
        </div>
      )}

      <div className="nl-rest-layout">
        <div>
          <nav className="nl-rest-sections" aria-label={t('sections')}>
            {sections.map(s => <a key={s.id} href={`#sec-${s.id}`} className="tag tag-outline">{s.name}</a>)}
          </nav>
          {data.combos.length > 0 && (
            <section id="sec-combos" aria-labelledby="h-combos">
              <h2 id="h-combos">{t('combos')}</h2>
              {data.combos.map(c => (
                <ComboRow key={c.id} t={t} c={c} data={data} open={openId === c.id} onToggle={() => setOpenId(openId === c.id ? null : c.id)}
                  disabled={!orderable || !c.availableNow}
                  onAdd={(qty, itemIds, names, unit) => guarded(() => { add(kitchenRef, { kind: 'combo', comboId: c.id, title: c.name, qty, unitCents: unit, optionIds: [], choices: names, itemIds }); setOpenId(null); })} />
              ))}
            </section>
          )}
          {data.sections.map(s => (
            <section key={s.id} id={`sec-${s.id}`} aria-labelledby={`h-${s.id}`}>
              <h2 id={`h-${s.id}`}>{s.name}</h2>
              {s.items.map(d => (
                <DishRow key={d.id} t={t} d={d} open={openId === d.id} onToggle={() => setOpenId(openId === d.id ? null : d.id)}
                  disabled={!orderable || d.soldOut}
                  onAdd={(qty, optionIds, note) => guarded(() => {
                    add(kitchenRef, { kind: 'item', itemId: d.id, title: d.name, qty, unitCents: d.priceCents + deltaOf(d.groups, optionIds), optionIds, choices: choiceNames(d.groups, optionIds), itemIds: [], ...(note ? { note } : {}) });
                    setOpenId(null);
                  })} />
              ))}
            </section>
          ))}
        </div>
        <OrderAside t={t} data={data} cart={cart && cart.merchantId === k.merchantId ? cart : null} onRemove={remove} />
      </div>

      {pending && (
        <Dialog open onClose={() => setPending(null)} title={t('otherKitchenTitle')}
          actions={<><Button variant="ghost" onClick={() => setPending(null)}>{t('cancel')}</Button><Button onClick={() => { clear(); pending(); setPending(null); }}>{t('startNew')}</Button></>}>
          <p>{t('otherKitchenBody', { name: cart?.name ?? '' })}</p>
        </Dialog>
      )}
    </div>
  );
}

function DishTags({ d }: { d: Dish }) {
  const { locale } = useLocale();
  const tags = [...d.dietary.map(x => TAGS[x]?.[locale] ?? x), ...d.allergens.map(a => containsLabel(a, locale))];
  return tags.length ? <div className="nl-food-tags">{tags.map(x => <Tag key={x} tone="neutral">{x}</Tag>)}</div> : null;
}

function DishRow({ t, d, open, disabled, onToggle, onAdd }: { t: T; d: Dish; open: boolean; disabled: boolean; onToggle: () => void; onAdd: (qty: number, optionIds: string[], note: string) => void }) {
  const { money } = useFormatters();
  const unavailable = d.soldOut ? t('soldOut') : !d.availableNow ? t('notNow') : null;
  return (
    <div className="nl-rest-dish">
      <div>
        <div className="nl-rest-dish-head"><strong>{d.name}</strong><span>{money(d.priceCents)}</span></div>
        {d.description && <p className="nl-rest-dish-desc">{d.description}</p>}
        <DishTags d={d} />
        {unavailable && <p className="nl-rest-muted">{unavailable}</p>}
        {open && <DishEditor t={t} d={d} onAdd={onAdd} onCancel={onToggle} />}
      </div>
      <button type="button" className="nl-rest-plus" aria-expanded={open} aria-label={t('add', { name: d.name })}
        disabled={disabled || !d.availableNow} onClick={onToggle}><span aria-hidden>+</span></button>
    </div>
  );
}

function DishEditor({ t, d, onAdd, onCancel }: { t: T; d: Dish; onAdd: (qty: number, optionIds: string[], note: string) => void; onCancel: () => void }) {
  const { money } = useFormatters();
  const [picked, setPicked] = useState<string[]>(() => defaults(d.groups));
  const [qty, setQty] = useState(1);
  const [note, setNote] = useState('');
  const [tried, setTried] = useState(false);
  const problems = pickProblems(d.groups, picked);
  const unit = d.priceCents + deltaOf(d.groups, picked);
  const submit = () => { setTried(true); if (problems.length === 0) onAdd(qty, picked, note.trim()); };
  return (
    <div className="nl-rest-editor">
      {visibleGroups(d.groups, picked).map(g => {
        const problem = tried ? problems.find(p => p.groupId === g.id) : undefined;
        return (
          <fieldset key={g.id} className="nl-rest-group" aria-invalid={problem ? true : undefined}>
            <legend>{g.name} <span className="nl-rest-rule">· {t(`rule_${g.rule}`, { count: g.count })} · {g.required ? t('required') : t('optional')}</span></legend>
            <div className="nl-rest-options">
              {g.options.map(o => (
                <button key={o.id} type="button" className="nl-chip" aria-pressed={picked.includes(o.id)} disabled={o.soldOut}
                  onClick={() => setPicked(p => toggle(d.groups, p, g.id, o.id))}>
                  {o.name}{o.deltaCents > 0 ? ` +${money(o.deltaCents)}` : ''}{o.soldOut ? ` · ${t('soldOut')}` : ''}
                </button>
              ))}
            </div>
            {problem && <div className="nl-error" role="alert">{t(problem.key, { count: problem.count, group: problem.group })}</div>}
          </fieldset>
        );
      })}
      <Field label={t('special')}>
        <TextInput value={note} onChange={e => setNote(e.target.value)} placeholder={t('specialPlaceholder')} maxLength={140} />
      </Field>
      <div className="nl-rest-editor-actions">
        <Stepper t={t} qty={qty} onChange={setQty} />
        <Button onClick={submit}>{t('addLine', { total: money(unit * qty) })}</Button>
        <Button variant="ghost" onClick={onCancel}>{t('cancel')}</Button>
      </div>
    </div>
  );
}

function Stepper({ t, qty, onChange }: { t: T; qty: number; onChange: (n: number) => void }) {
  return (
    <div className="nl-rest-stepper" role="group" aria-label={t('qty')}>
      <button type="button" aria-label={t('less')} onClick={() => onChange(Math.max(1, qty - 1))} disabled={qty <= 1}>−</button>
      <span aria-live="polite">{qty}</span>
      <button type="button" aria-label={t('more')} onClick={() => onChange(Math.min(20, qty + 1))} disabled={qty >= 20}>+</button>
    </div>
  );
}

function ComboRow({ t, c, data, open, disabled, onToggle, onAdd }: {
  t: T; c: Combo; data: Restaurant; open: boolean; disabled: boolean; onToggle: () => void;
  onAdd: (qty: number, itemIds: string[], names: string[], unit: number) => void;
}) {
  const { money } = useFormatters();
  const dishes = new Map(data.sections.flatMap(s => s.items).map(d => [d.id, d]));
  const units = c.slots.flatMap(s => Array.from({ length: s.qty }, (_, i) => ({ slot: s, n: i + 1 })));
  const [picks, setPicks] = useState<string[]>(() => units.map(u => u.slot.itemIds[0] ?? ''));
  const [qty, setQty] = useState(1);
  const [tried, setTried] = useState(false);
  const complete = picks.every(p => p);
  const separately = picks.reduce((n, id) => n + (dishes.get(id)?.priceCents ?? 0), 0);
  const unit = c.pricing === 'fixed' ? (c.priceCents ?? c.fromCents) : Math.round(separately * (10_000 - (c.discountBps ?? 0)) / 10_000);
  return (
    <div className="nl-rest-dish">
      <div>
        <div className="nl-rest-dish-head"><strong>{c.name}</strong><span>{money(c.fromCents)}</span></div>
        <p className="nl-rest-dish-desc">{c.slots.map(s => s.label).join(' + ')}</p>
        {c.saveCents > 0 && <div className="nl-food-tags"><Tag tone="highlight">{t('combosSave', { price: money(c.fromCents), save: money(c.saveCents) })}</Tag></div>}
        {!c.availableNow && <p className="nl-rest-muted">{t('notNow')}</p>}
        {open && (
          <div className="nl-rest-editor">
            {units.map((u, i) => (
              <Field key={i} label={t('slotPick', { label: u.slot.label, n: u.n, qty: u.slot.qty })}>
                <select className="input" value={picks[i]} onChange={e => setPicks(p => p.map((x, j) => (j === i ? e.target.value : x)))}>
                  {u.slot.itemIds.map(id => <option key={id} value={id}>{dishes.get(id)?.name ?? id}</option>)}
                </select>
              </Field>
            ))}
            {tried && !complete && <div className="nl-error" role="alert">{t('comboPick')}</div>}
            <div className="nl-rest-editor-actions">
              <Stepper t={t} qty={qty} onChange={setQty} />
              <Button onClick={() => { setTried(true); if (complete) onAdd(qty, picks, picks.map(id => dishes.get(id)?.name ?? id), unit); }}>{t('addLine', { total: money(unit * qty) })}</Button>
              <Button variant="ghost" onClick={onToggle}>{t('cancel')}</Button>
            </div>
          </div>
        )}
      </div>
      <button type="button" className="nl-rest-plus" aria-expanded={open} aria-label={t('add', { name: c.name })} disabled={disabled} onClick={onToggle}><span aria-hidden>+</span></button>
    </div>
  );
}

function OrderAside({ t, data, cart, onRemove }: { t: T; data: Restaurant; cart: FoodCart | null; onRemove: (key: string) => void }) {
  const { money } = useFormatters();
  const navigate = useNavigate();
  const k = data.kitchen;
  const sub = subtotalOf(cart);
  const service = Math.round(sub * data.serviceFeeBps / 10_000);
  const tax = Math.round((sub + service) * GST_BPS / 10_000);
  const total = sub + k.deliveryFeeCents + service + tax;
  const below = sub < data.minOrderCents;
  return (
    <aside className="nl-rest-aside" aria-labelledby="order-title">
      <h2 id="order-title">{t('yourOrder', { name: k.name })}</h2>
      <p className="nl-rest-muted">{k.open ? t('delivering') : t('scheduled')} · {t('etaRange', { from: k.etaFromMin, to: k.etaToMin })}</p>
      {!cart || cart.lines.length === 0 ? (
        <p className="nl-rest-muted">{t('emptyOrder', { min: money(data.minOrderCents, { whole: true }) })}</p>
      ) : (
        <>
          <ul className="nl-rest-lines">
            {cart.lines.map(l => (
              <li key={l.key}>
                <span><strong>{l.qty}×</strong> {l.title}{(l.choices.length > 0 || l.note) && <span className="nl-rest-line-sub">{[...l.choices, l.note].filter(Boolean).join(', ')}</span>}</span>
                <span className="nl-rest-line-end">{money(l.unitCents * l.qty)}<button type="button" aria-label={t('remove', { name: l.title })} onClick={() => onRemove(l.key)}>×</button></span>
              </li>
            ))}
          </ul>
          <dl className="nl-rest-totals">
            <div><dt>{t('items')}</dt><dd>{money(sub)}</dd></div>
            <div><dt>{t('deliveryDirect')}</dt><dd>{money(k.deliveryFeeCents)}</dd></div>
            <div><dt>{t('serviceFee')}</dt><dd>{money(service)}</dd></div>
            <div><dt>{t('gst', { rate: 5 })}</dt><dd>{money(tax)}</dd></div>
            <div className="nl-rest-total"><dt>{t('total')}</dt><dd>{money(total)}</dd></div>
          </dl>
          <Button block disabled={below} onClick={() => void navigate({ to: '/food/checkout' })}>
            {below ? t('reachMin', { amount: money(data.minOrderCents - sub) }) : t('checkout', { total: money(total) })}
          </Button>
        </>
      )}
    </aside>
  );
}
