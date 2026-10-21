import { useRef, useState } from 'react';
import { Link, useNavigate } from '@tanstack/react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { isNotFound, newIdempotencyKey, ValidationError } from '@northline/client';
import { Alert, Button, Dialog, EmptyState, ErrorState, Field, Skeleton, SiteLink, TextInput, useFormatters } from '@northline/ui';
import { StripePayment } from '../cart/Payment';
import { StepUpDialog } from '../cart/StepUpDialog';
import type { Started as CartStarted } from '../cart/api';
import { signInHref, useViewer } from '../session/api';
import { problemCode, type FoodStarted } from './api';
import { asCartStarted, BankStep } from './CheckoutScreen';
import {
  cancelGroup, checkoutGroup, confirmGroup, confirmShare, groupPath, groupQuery, joinGroup, leaveGroup, lockGroup, payShare,
  placeGroup, previewQuery, removeFromGroup, unlockGroup, type GroupView, type Member, type ShareStarted, type Tip,
} from './groupApi';
import { useGroupT } from './groupMessages';
import { useFoodT } from './messages';

type T = ReturnType<typeof useGroupT>;
const TIPS: readonly Tip[] = [{ kind: 'none', value: 0 }, { kind: 'amount', value: 200 }, { kind: 'amount', value: 400 }, { kind: 'percent', value: 15 }, { kind: 'amount', value: 600 }];
type Paying =
  | { kind: 'all'; started: FoodStarted }
  | { kind: 'share'; userId: string; started: ShareStarted };
type Retry = { run: (proof?: string) => Promise<void>; mode: 'required' | 'enrol' };

/**
 * A group food order (S-159) at its share link `/food/group/<code>`: someone signed in who isn't in it yet sees the
 * kitchen and the host and joins; members see everyone's dishes (and add theirs on the restaurant page), the host locks
 * the cart, then pays for everyone (S-57's checkout) or each person pays their share; once every share is paid the
 * host sends it to the kitchen — one ticket, one delivery — and everyone follows the same order here.
 */
export function GroupScreen({ code }: { code: string }) {
  const t = useGroupT();
  const { user, loading } = useViewer();
  const preview = useQuery({ ...previewQuery(code), enabled: !!user, retry: false });
  const [joined, setJoined] = useState<string | null>(null);
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  if (loading || (user && preview.isPending)) return <div className="nl-page"><Skeleton width={360} height={36} /><Skeleton height={200} style={{ marginTop: 20 }} /></div>;
  if (!user) {
    return (
      <div className="nl-page nl-grp">
        <EmptyState action={<SiteLink href={signInHref(groupPath(code))} className="btn btn-primary">{t('joinSignIn')}</SiteLink>}>{t('shareHint')}</EmptyState>
      </div>
    );
  }
  if (preview.isError) {
    return (
      <div className="nl-page nl-grp">
        {isNotFound(preview.error)
          ? <><Alert tone="error" role="alert">{t('notFound')}</Alert><CodeForm t={t} /></>
          : <ErrorState message={t('loadError')} onRetry={() => void preview.refetch()} />}
      </div>
    );
  }
  const p = preview.data!;
  const groupId = joined ?? p.groupId;
  if (groupId) return <GroupPanel id={groupId} />;

  const join = async () => {
    setBusy(true); setError(undefined);
    try {
      const view = await joinGroup(p.code);
      qc.setQueryData(groupQuery(view.id).queryKey, view);
      setJoined(view.id);
    } catch (e) { setError(e instanceof Error ? e.message : t('err_generic')); } finally { setBusy(false); }
  };
  return (
    <div className="nl-page nl-grp">
      <h1 className="nl-page-title">{t('joinTitle', { host: p.hostFirstName, kitchen: p.kitchen })}</h1>
      <p>{t('joinBody', { host: p.hostFirstName })}</p>
      <p className="nl-rest-muted">{t('people', { count: p.people, max: p.groupMax })}</p>
      {error && <p className="nl-error" role="alert">{error}</p>}
      <Button onClick={() => void join()} disabled={busy} aria-busy={busy}>{t('join')}</Button>
    </div>
  );
}

/** "Have a code?" — the code typed instead of the link. */
function CodeForm({ t }: { t: T }) {
  const navigate = useNavigate();
  const [value, setValue] = useState('');
  return (
    <form className="nl-grp-code" onSubmit={e => { e.preventDefault(); if (value.trim()) void navigate({ to: '/food/group/$code', params: { code: value.trim() } }); }}>
      <Field label={t('codeLabel')}><TextInput value={value} onChange={e => setValue(e.target.value)} autoCapitalize="characters" maxLength={12} /></Field>
      <Button type="submit" variant="secondary">{t('openCode')}</Button>
    </form>
  );
}

function GroupPanel({ id }: { id: string }) {
  const t = useGroupT();
  const tf = useFoodT();
  const { money, date } = useFormatters();
  const qc = useQueryClient();
  const q = useQuery(groupQuery(id));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [tip, setTip] = useState(2);
  const [paying, setPaying] = useState<Paying | null>(null);
  const [stepUp, setStepUp] = useState<Retry | null>(null);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [copied, setCopied] = useState(false);
  const keys = useRef({ start: newIdempotencyKey(), confirm: newIdempotencyKey() });

  if (q.isPending) return <div className="nl-page"><Skeleton width={360} height={36} /><Skeleton height={200} style={{ marginTop: 20 }} /></div>;
  if (q.isError) return <div className="nl-page"><ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /></div>;
  const g = q.data;
  const set = (view: GroupView) => qc.setQueryData(groupQuery(id).queryKey, view);
  const me = g.members.find(m => m.userId === g.you);
  const hostName = g.members.find(m => m.host)?.name ?? '';
  const open = g.state === 'open';
  const waiting = g.state === 'locked' || g.state === 'paying';
  const link = typeof window === 'undefined' ? groupPath(g.code) : `${window.location.origin}${groupPath(g.code)}`;

  const failed = (e: unknown, retry?: (proof?: string) => Promise<void>) => {
    const code = problemCode(e);
    if (retry && (code === 'step_up_required' || code === 'second_factor_required')) { setStepUp({ run: retry, mode: code === 'step_up_required' ? 'required' : 'enrol' }); return; }
    if (code === 'kitchen_closed') { setError(t('err_closed')); return; }
    if (e instanceof ValidationError) { setError(e.errors.map(x => x.message).join(' ')); return; }
    setError(e instanceof Error && code ? e.message : t('err_generic'));
  };
  const act = async (run: () => Promise<GroupView>) => {
    setBusy(true); setError(undefined);
    try { set(await run()); } catch (e) { failed(e); } finally { setBusy(false); }
  };
  const payAll = async (proof?: string) => {
    setBusy(true); setError(undefined);
    try {
      const started = await checkoutGroup(id, TIPS[tip]!, keys.current.start, proof);
      keys.current.start = newIdempotencyKey();
      setPaying({ kind: 'all', started });
    } catch (e) { failed(e, payAll); } finally { setBusy(false); }
  };
  const payFor = (userId: string) => async (proof?: string) => {
    setBusy(true); setError(undefined);
    try {
      const started = await payShare(id, userId, keys.current.start, proof);
      keys.current.start = newIdempotencyKey();
      setPaying({ kind: 'share', userId, started });
    } catch (e) { failed(e, payFor(userId)); } finally { setBusy(false); }
  };
  const paid = async (p: Paying) => {
    setBusy(true); setError(undefined);
    try {
      set(p.kind === 'all' ? await confirmGroup(id, keys.current.confirm) : await confirmShare(id, p.userId, keys.current.confirm));
      keys.current.confirm = newIdempotencyKey();
      setPaying(null);
    } catch (e) { failed(e); } finally { setBusy(false); }
  };
  const copy = async () => {
    try { await navigator.clipboard.writeText(link); setCopied(true); } catch { setCopied(false); }
  };
  const total = (m: Member) => m.share?.totalCents ?? m.subtotalCents;

  return (
    <div className="nl-page nl-grp">
      <h1 className="nl-page-title">{t('groupTitle', { kitchen: g.kitchen })}</h1>
      <p className="nl-rest-muted">
        {[t('hostedBy', { name: hostName }), g.mode === 'pickup' ? t('modePickup') : g.area ? t('modeDelivery', { area: g.area }) : t('modeDeliveryNoArea'),
          g.payment === 'split' ? t('paySplit') : t('payHost'), t('people', { count: g.members.length, max: g.groupMax })].join(' · ')}
      </p>

      {g.state === 'cancelled' && <Alert tone="neutral" role="status">{t('cancelled')}</Alert>}
      {g.state === 'expired' && <Alert tone="neutral" role="status">{t('expired')}</Alert>}
      {g.state === 'placed' && (
        <Alert tone="highlight" role="status" actions={g.host && g.orderId ? <Link to="/food/orders/$orderId" params={{ orderId: g.orderId }} className="btn btn-secondary">{t('track')}</Link> : undefined}>
          {t('placed', { ref: g.ref ?? '' })}{g.tracking ? ` · ${t(`stage_${g.tracking.stage}`)}` : ''}
        </Alert>
      )}
      {waiting && !g.host && <Alert tone="info" role="status">{t('locked')} {g.payment === 'host' ? t('lockedWaitHost', { host: hostName }) : ''}</Alert>}

      {open && g.host && (
        <section className="nl-grp-share" aria-labelledby="grp-share">
          <h2 id="grp-share">{t('share')}</h2>
          <p>{t('shareHint')}</p>
          <div className="nl-grp-link">
            <code>{link}</code>
            <Button variant="secondary" onClick={() => void copy()}>{copied ? t('copied') : t('copyLink')}</Button>
          </div>
          <p className="nl-rest-muted">{t('code', { code: g.code })} · {t('expires', { time: date(g.expiresAt, 'time') })}</p>
        </section>
      )}

      <section aria-labelledby="grp-people">
        <h2 id="grp-people" className="nl-sr-only">{t('people', { count: g.members.length, max: g.groupMax })}</h2>
        <ul className="nl-grp-members">
          {g.members.map(m => (
            <li key={m.userId} className="nl-grp-member">
              <div className="nl-grp-member-head">
                <strong>{m.userId === g.you ? t('you') : m.name}</strong>
                {m.host && <span className="tag tag-neutral">{t('host')}</span>}
                <span className="nl-grp-member-total">{money(total(m))}</span>
              </div>
              {m.items.length === 0 ? <p className="nl-rest-muted">{t('nothingYet')}</p> : (
                <ul className="nl-rest-lines">
                  {m.items.map(i => (
                    <li key={i.id}>
                      <span><strong>{i.qty}×</strong> {i.title}{(i.choices.length > 0 || i.note) && <span className="nl-rest-line-sub">{[...i.choices, i.note].filter(Boolean).join(', ')}</span>}</span>
                      <span className="nl-rest-line-end">{money(i.totalCents)}
                        {open && (m.userId === g.you || g.host) && <button type="button" aria-label={t('remove', { name: i.title })} disabled={busy} onClick={() => void act(() => removeFromGroup(id, i.id))}>×</button>}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
              {m.share && (
                <div className="nl-grp-share-line">
                  <span className="nl-rest-muted">{t('shareLine', {
                    dishes: money(m.share.subtotalCents), fees: money(m.share.deliveryFeeCents + m.share.serviceFeeCents), tax: money(m.share.taxCents + m.share.feeTaxCents),
                    tip: m.share.tipCents > 0 ? t('shareTip', { tip: money(m.share.tipCents) }) : '',
                  })}</span>
                  {m.share.state === 'authorized'
                    ? <span className="tag tag-success">{m.share.paidBy ? t('paidBy', { name: m.share.paidBy }) : t('paid')}</span>
                    : m.share.state === 'voided' ? null
                    : (m.userId === g.you || g.host) && waiting && !paying
                      ? <Button size="sm" disabled={busy} onClick={() => void payFor(m.userId)()}>{m.userId === g.you ? t('payMine', { total: money(m.share.totalCents) }) : t('payFor', { name: m.name, total: money(m.share.totalCents) })}</Button>
                      : <span className="tag tag-neutral">{t('due')}</span>}
                </div>
              )}
            </li>
          ))}
        </ul>
        <p className="nl-grp-subtotal"><span>{t('subtotal')}</span><strong>{money(g.subtotalCents)}</strong></p>
      </section>

      {error && <p className="nl-error" role="alert">{error}</p>}

      {paying && (paying.started.mode === 'stripe'
        ? <StripePayment started={paying.kind === 'all' ? asCartStarted(paying.started) : shareAsCart(paying.started)} busy={busy} onPaid={() => void paid(paying)} onError={m => setError(m)} />
        : <BankStep t={tf} total={money(paying.kind === 'all' ? paying.started.totals.totalCents : paying.started.totalCents)} busy={busy} onApprove={() => void paid(paying)} />)}

      <div className="nl-grp-actions">
        {open && g.kitchenSlug && <Link to="/food/$kitchen" params={{ kitchen: g.kitchenSlug }} search={{ group: g.id }} className="btn btn-primary">{t('addDishes')}</Link>}
        {open && g.host && (
          <>
            {g.mode === 'delivery' && (
              <fieldset className="nl-fco-chips"><legend>{t('tip')}</legend>
                {TIPS.map((x, i) => <button key={i} type="button" className="nl-chip" aria-pressed={tip === i} onClick={() => setTip(i)}>{x.kind === 'none' ? t('noTip') : x.kind === 'percent' ? `${x.value}%` : money(x.value, { whole: true })}</button>)}
              </fieldset>
            )}
            <Button variant="secondary" disabled={busy || g.subtotalCents === 0} onClick={() => void act(() => lockGroup(id, g.mode === 'delivery' ? TIPS[tip]! : TIPS[0]!))}>{t('lock')}</Button>
            <p className="nl-rest-muted">{t('lockHint')}</p>
          </>
        )}
        {waiting && g.host && g.payment === 'host' && !paying && <Button disabled={busy} aria-busy={busy} onClick={() => void payAll()}>{t('payAll', { total: money(g.subtotalCents) })}</Button>}
        {waiting && g.host && g.payment === 'split' && (
          g.canPlace
            ? <Button disabled={busy} aria-busy={busy} onClick={() => void act(() => placeGroup(id))}>{busy ? t('sending') : t('sendKitchen')}</Button>
            : <p className="nl-rest-muted" role="status">{t('waitingShares', { count: g.members.filter(m => m.share && m.share.state !== 'authorized').length })}</p>
        )}
        {waiting && g.host && !paying && <Button variant="ghost" disabled={busy} onClick={() => void act(() => unlockGroup(id))}>{t('unlock')}</Button>}
        {(open || waiting) && g.host && <Button variant="ghost" disabled={busy} onClick={() => setConfirmCancel(true)}>{t('cancelGroup')}</Button>}
        {open && !g.host && me && <Button variant="ghost" disabled={busy} onClick={() => void act(() => leaveGroup(id))}>{t('leave')}</Button>}
      </div>

      {confirmCancel && (
        <Dialog open onClose={() => setConfirmCancel(false)} title={t('cancelGroup')}
          actions={<><Button variant="ghost" onClick={() => setConfirmCancel(false)}>{t('keep')}</Button><Button variant="danger" onClick={() => { setConfirmCancel(false); void act(() => cancelGroup(id)); }}>{t('confirmCancel')}</Button></>}>
          <p>{t('cancelConfirm')}</p>
        </Dialog>
      )}
      {stepUp && <StepUpDialog mode={stepUp.mode} onClose={() => setStepUp(null)} onProof={proof => { const r = stepUp; setStepUp(null); void r.run(proof); }} />}
    </div>
  );
}

/** S-51's Payment Element takes the cart's shape: one intent, the share. */
function shareAsCart(s: ShareStarted): CartStarted {
  return {
    checkoutId: s.shareId, orderId: s.shareId, ref: '', totalCents: s.totalCents, expiresAt: '',
    payment: { provider: s.mode, publishableKey: s.publishableKey ?? null },
    intents: [{ paymentIntent: s.paymentIntent, clientSecret: s.clientSecret ?? null, status: s.status === 'authorized' ? 'requires_confirmation' : s.status, amountCents: s.totalCents }],
  };
}
