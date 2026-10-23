import { useState } from 'react';
import { Link, useNavigate } from '@tanstack/react-router';
import { useQuery, useQueryClient, useSuspenseQuery } from '@tanstack/react-query';
import { ValidationError } from '@northline/client';
import { Alert, Button, Dialog, Field, OptionCard, RadioGroup, Tag, TextInput, useFormatters, useLocale } from '@northline/ui';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { signInHref, useViewer } from '../session/api';
import { addToGroup, groupQuery, startGroup, type GroupDish } from './groupApi';
import { useGroupT } from './groupMessages';
import { useStorefrontVisit } from '../../lib/visits';
import { restaurantQuery, type Combo, type Dish, type Restaurant } from './api';
import { subtotalOf, useFoodCart, type FoodCart } from './foodCart';
import { CUISINE_NAMES, containsLabel, TAGS, useFoodT } from './messages';
import { Crumbs } from '../pages/Crumbs';
import { cuisineSlot } from '@northline/media';
import { PhotoWell } from '../art/PhotoWell';
import { Scene } from '../art/Scene';
import { choiceNames, defaults, deltaOf, pickProblems, toggle, visibleGroups } from './modifiers';

type T = ReturnType<typeof useFoodT>;

/**
 * Restaurant (design 06 `restaurant`, S-57): the kitchen's banner, its live menus by section (combos first), a dish's
 * modifier groups with their pick rules, special instructions and quantity, and "Your order" — the food cart, one
 * kitchen at a time, with the $15 minimum. Server-rendered (SEO); the order lives in the browser.
 */
export function RestaurantScreen({ slug, group }: { slug: string; group?: string }) {
  const t = useFoodT();
  const { locale } = useLocale();
  const { money, date } = useFormatters();
  const { data } = useSuspenseQuery(restaurantQuery(slug));
  useStorefrontVisit(slug); // S-75: the kitchen's public page counts like any business page
  const { cart, add, remove, clear } = useFoodCart();
  const [openId, setOpenId] = useState<string | null>(null);
  const [pending, setPending] = useState<null | (() => void)>(null);
  // S-159: adding to a group order (`?group=<id>`): dishes go to the shared cart, not this browser's
  const groupAdd = useGroupAdd(group);
  const k = data.kitchen;
  const kitchenRef = { merchantId: k.merchantId, slug, name: k.name };
  const cuisine = k.cuisines.map(c => CUISINE_NAMES[c]?.[locale] ?? c).join(', ');

  /** Adding from another kitchen asks first (a food order comes from one kitchen). */
  const guarded = (run: () => void) => {
    if (cart && cart.merchantId !== k.merchantId && cart.lines.length > 0) setPending(() => run);
    else run();
  };
  const addItem = (d: Dish, qty: number, optionIds: string[], note: string) => {
    if (groupAdd.active) { void groupAdd.add({ itemId: d.id, qty, optionIds, note: note || null }, d.name); setOpenId(null); return; }
    guarded(() => {
      add(kitchenRef, { kind: 'item', itemId: d.id, title: d.name, qty, unitCents: d.priceCents + deltaOf(d.groups, optionIds), optionIds, choices: choiceNames(d.groups, optionIds), itemIds: [], ...(note ? { note } : {}) });
      setOpenId(null);
    });
  };
  const addCombo = (c: Combo, qty: number, itemIds: string[], names: string[], unit: number) => {
    if (groupAdd.active) { void groupAdd.add({ comboId: c.id, qty, itemIds }, c.name); setOpenId(null); return; }
    guarded(() => { add(kitchenRef, { kind: 'combo', comboId: c.id, title: c.name, qty, unitCents: unit, optionIds: [], choices: names, itemIds }); setOpenId(null); });
  };

  const orderable = k.open || data.slots.length > 0;
  const sections = [
    ...(data.combos.length ? [{ id: 'combos', name: t('combos') }] : []),
    ...data.sections.map(s => ({ id: s.id, name: s.name })),
  ];

  return (
    <div className="nl-rest">
      <Crumbs label={t('breadcrumb')} items={[{ label: t('home'), href: '/' }, { label: t('title'), href: '/food' }, ...(cuisine ? [{ label: cuisine, href: '/food' }] : []), { label: k.name }]} />
      <span className="nl-rest-cover" aria-hidden><Scene slot={[cuisineSlot(k.cuisines[0]), 'banner/food']} subject={k.cuisines[0] ?? 'food'} fit="contain" sizes="(min-width: 1200px) 1140px, 100vw" priority /></span>
      <header className="nl-rest-banner">
        <div>
          <h1>{k.name}</h1>
          <p>{[cuisine, data.address, k.reviews > 0 ? t('rating', { rating: k.rating.toFixed(1), count: k.reviews }) : null].filter(Boolean).join(' · ')}</p>
        </div>
        <div className="nl-rest-badges">
          <span className="tag tag-neutral">{t('etaRange', { from: k.etaFromMin, to: k.etaToMin })} · {money(k.deliveryFeeCents)}</span>
          {data.ahsVerified && <span className="tag tag-success">{t('ahs')}</span>}
          <span className="tag tag-neutral">{t('minOrder', { amount: money(data.minOrderCents, { whole: true }) })}</span>
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
                  onAdd={(qty, itemIds, names, unit) => addCombo(c, qty, itemIds, names, unit)} />
              ))}
            </section>
          )}
          {data.sections.map(s => (
            <section key={s.id} id={`sec-${s.id}`} aria-labelledby={`h-${s.id}`}>
              <h2 id={`h-${s.id}`}>{s.name}</h2>
              {s.items.map(d => (
                <DishRow key={d.id} t={t} d={d} open={openId === d.id} onToggle={() => setOpenId(openId === d.id ? null : d.id)}
                  disabled={!orderable || d.soldOut}
                  onAdd={(qty, optionIds, note) => addItem(d, qty, optionIds, note)} />
              ))}
            </section>
          ))}
        </div>
        {groupAdd.active
          ? <GroupAside groupAdd={groupAdd} />
          : <OrderAside t={t} data={data} cart={cart && cart.merchantId === k.merchantId ? cart : null} onRemove={remove} />}
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
        disabled={disabled || !d.availableNow} onClick={onToggle}><PhotoWell subject={d.name} /><span className="nl-rest-plus-sign" aria-hidden>+</span></button>
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
      <button type="button" className="nl-rest-plus" aria-expanded={open} aria-label={t('add', { name: c.name })} disabled={disabled} onClick={onToggle}><PhotoWell subject={c.name} /><span className="nl-rest-plus-sign" aria-hidden>+</span></button>
    </div>
  );
}

function OrderAside({ t, data, cart, onRemove }: { t: T; data: Restaurant; cart: FoodCart | null; onRemove: (key: string) => void }) {
  const { money } = useFormatters();
  const { locale } = useLocale();
  const navigate = useNavigate();
  const k = data.kitchen;
  const sub = subtotalOf(cart);
  const service = Math.round(sub * data.serviceFeeBps / 10_000);
  // an estimate at the kitchen province's rate (api `taxBps`); checkout prices the tax for the delivery address
  const tax = Math.round((sub + service) * data.taxBps / 10_000);
  const total = sub + k.deliveryFeeCents + service + tax;
  const below = sub < data.minOrderCents;
  return (
    <aside className="nl-rest-aside" aria-labelledby="order-title">
      {data.groupOrders && <StartGroup data={data} />}
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
            <div><dt>{t('taxRate', { rate: (data.taxBps / 100).toLocaleString(locale === 'fr' ? 'fr-CA' : 'en-CA', { maximumFractionDigits: 3 }) })}</dt><dd>{money(tax)}</dd></div>
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

type GroupAdd = ReturnType<typeof useGroupAdd>;

/** S-159: the restaurant page adding to a group order — its dishes, and a message per dish added. */
function useGroupAdd(groupId: string | undefined) {
  const qc = useQueryClient();
  const q = useQuery({ ...groupQuery(groupId ?? ''), enabled: !!groupId, refetchInterval: false });
  const [message, setMessage] = useState<{ tone: 'success' | 'danger'; text: string }>();
  const tg = useGroupT();
  const active = !!groupId && q.isSuccess && q.data.state === 'open';
  const add = async (dish: GroupDish, name: string) => {
    if (!groupId) return;
    try {
      const view = await addToGroup(groupId, dish);
      qc.setQueryData(groupQuery(groupId).queryKey, view);
      setMessage({ tone: 'success', text: tg('added', { name }) });
    } catch (e) {
      setMessage({ tone: 'danger', text: e instanceof ValidationError ? e.errors.map(x => x.message).join(' ') : e instanceof Error ? e.message : tg('err_generic') });
    }
  };
  return { active, group: q.data, add, message };
}

function GroupAside({ groupAdd }: { groupAdd: GroupAdd }) {
  const tg = useGroupT();
  const { money } = useFormatters();
  const g = groupAdd.group!;
  const mine = g.members.find(m => m.userId === g.you);
  return (
    <aside className="nl-rest-aside" aria-labelledby="group-title">
      <h2 id="group-title">{tg('addingTo')}</h2>
      <p className="nl-rest-muted">{tg('groupTitle', { kitchen: g.kitchen })}</p>
      {groupAdd.message && <p className={groupAdd.message.tone === 'danger' ? 'nl-error' : 'nl-rest-muted'} role="status">{groupAdd.message.text}</p>}
      {mine && mine.items.length > 0 && (
        <ul className="nl-rest-lines">
          {mine.items.map(i => <li key={i.id}><span><strong>{i.qty}×</strong> {i.title}</span><span>{money(i.totalCents)}</span></li>)}
        </ul>
      )}
      <Link to="/food/group/$code" params={{ code: g.code }} className="btn btn-primary btn-block">{tg('backToGroup')}</Link>
    </aside>
  );
}

/** S-159: "Start a group order" — delivery to the saved address or pickup, who pays; then the group's page. */
function StartGroup({ data }: { data: Restaurant }) {
  const tg = useGroupT();
  const navigate = useNavigate();
  const { user } = useViewer();
  const { location } = useDeliveryLocation();
  const k = data.kitchen;
  const delivers = k.fulfilment.includes('courier');
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<'delivery' | 'pickup'>(delivers ? 'delivery' : 'pickup');
  const [payment, setPayment] = useState<'host' | 'split'>('host');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const hasAddress = location.status === 'saved' && !!location.street && location.lat != null && location.lng != null;
  const here = typeof window === 'undefined' ? `/food/${k.slug ?? ''}` : window.location.pathname;
  const start = async () => {
    setBusy(true); setError(undefined);
    try {
      const view = await startGroup({
        merchantId: k.merchantId, mode, scheduledFor: null, payment,
        delivery: mode === 'pickup' || !hasAddress ? null : {
          street: location.street!, ...(location.unit ? { unit: location.unit } : {}), ...(location.city ? { city: location.city } : {}),
          province: location.province ?? data.province ?? '', ...(location.postalCode ? { postalCode: location.postalCode } : {}),
          lat: location.lat!, lng: location.lng!, ...(location.zoneId ? { zoneId: location.zoneId } : {}), ...(location.zone ? { zone: location.zone } : {}),
          dropoff: 'hand', extras: [],
        },
      });
      void navigate({ to: '/food/group/$code', params: { code: view.code } });
    } catch (e) {
      setError(e instanceof ValidationError ? e.errors.map(x => x.message).join(' ') : e instanceof Error ? e.message : tg('err_generic'));
    } finally { setBusy(false); }
  };
  return (
    <div className="nl-rest-group-bar">
      {user
        ? <Button variant="secondary" block onClick={() => setOpen(true)}>{tg('startGroup')}</Button>
        : <a href={signInHref(here)} className="btn btn-secondary btn-block">{tg('startGroup')}</a>}
      <p className="nl-rest-muted">{tg('startGroupHint')}</p>
      {open && (
        <Dialog open onClose={() => setOpen(false)} title={tg('startTitle', { kitchen: k.name })}
          actions={<><Button variant="ghost" onClick={() => setOpen(false)}>{tg('cancel')}</Button>
            <Button disabled={busy || (mode === 'delivery' && !hasAddress)} aria-busy={busy} onClick={() => void start()}>{tg('start')}</Button></>}>
          <div className="nl-grp-start">
            <RadioGroup aria-label={tg('howGet')}>
              {delivers && <OptionCard selected={mode === 'delivery'} role="radio" aria-checked={mode === 'delivery'} title={tg('delivery')} description={hasAddress ? location.street ?? '' : tg('needAddress')} onClick={() => setMode('delivery')} />}
              {k.fulfilment.includes('pickup') && <OptionCard selected={mode === 'pickup'} role="radio" aria-checked={mode === 'pickup'} title={tg('pickup')} description={data.address ?? ''} onClick={() => setMode('pickup')} />}
            </RadioGroup>
            {mode === 'delivery' && !hasAddress && <Link to="/location" search={{ next: here }} className="btn btn-secondary">{tg('addAddress')}</Link>}
            <RadioGroup aria-label={tg('whoPays')}>
              <OptionCard selected={payment === 'host'} role="radio" aria-checked={payment === 'host'} title={tg('hostPays')} description={tg('hostPaysDesc')} onClick={() => setPayment('host')} />
              <OptionCard selected={payment === 'split'} role="radio" aria-checked={payment === 'split'} title={tg('splitPays')} description={tg('splitPaysDesc')} onClick={() => setPayment('split')} />
            </RadioGroup>
            {error && <p className="nl-error" role="alert">{error}</p>}
          </div>
        </Dialog>
      )}
    </div>
  );
}
