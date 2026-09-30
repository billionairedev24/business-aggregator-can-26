import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation } from '@tanstack/react-router';
import { ApiError, isNotFound, newIdempotencyKey, ValidationError } from '@northline/client';
import { ErrorState, Field, Skeleton, TextArea, TextInput, useFormatters, useLocale } from '@northline/ui';
import type { Confirmation } from '../booking/api';
import { StripeCard } from '../booking/StripeCard';
import { StepUpDialog } from '../cart/StepUpDialog';
import { signInHref, useViewer } from '../session/api';
import { NotFound } from '../shell/NotFound';
import { percent, rating } from '../services/format';
import { acceptQuote, confirmAcceptance, declineQuote, quoteQuery, type Acceptance, type QuotePage as Page, type Visit } from './api';
import { useQuotesT } from './messages';

/** design 06 `quote`: one provider's itemized quote — every line, scope, exclusions, warranty, deposit, validity. */
export function QuotePage({ quoteId }: { quoteId: string }) {
  const t = useQuotesT();
  const { locale } = useLocale();
  const q = useQuery(quoteQuery(quoteId, locale));
  if (q.isPending) return <QuoteSkeleton />;
  if (q.isError) return isNotFound(q.error) ? <NotFound /> : <div className="nl-page nl-qt"><ErrorState message={t('loadError')} onRetry={() => void q.refetch()} /></div>;
  return <QuoteView page={q.data} />;
}

const tierKey = (tier: string) => `tier_${tier === 'master' || tier === 'trusted' ? tier : 'registered'}` as const;

function QuoteView({ page }: { page: Page }) {
  const t = useQuotesT();
  const { locale } = useLocale();
  const { money, date } = useFormatters();
  const { quote: q, provider: p } = page;
  const state = q.expired ? 'expired' : q.state;
  const tone = state === 'accepted' ? 'tag-accent' : state === 'sent' || state === 'viewed' ? 'tag-accent-2' : 'tag-neutral';
  const pct = (bps: number) => percent(bps / 100, locale);
  const balance = q.totalCents - q.depositCents;
  const hours = q.durationMin ? `${(q.durationMin / 60).toLocaleString(locale === 'fr' ? 'fr-CA' : 'en-CA', { maximumFractionDigits: 1 })} h` : '';
  const meta = [t(tierKey(p.tier)), p.reviewCount > 0 ? `★ ${rating(p.rating, locale)} (${p.reviewCount})` : null, p.onTimePct != null ? t('trust', { onTime: percent(p.onTimePct, locale), disputes: percent(p.disputePct ?? 0, locale) }) : null].filter(Boolean).join(' · ');
  return (
    <div className="nl-page nl-qt">
      <nav aria-label={t('breadcrumb')} className="nl-qt-crumbs">
        <Link to="/quotes/requests/$requestId" params={{ requestId: q.requestId }}>{t('crumbRequest')}</Link> › <span aria-current="page">{t('crumbQuote', { ref: q.ref })}</span>
      </nav>
      <div className="nl-qt-split">
        <div className="nl-qt-main">
          <div className="nl-qt-status">
            <span className={`tag ${tone}`}>{t(`state_${state}` as 'state_sent')}</span>
            {q.validUntil ? <span className="nl-qt-muted">{q.expired ? t('expiredOn', { when: date(q.validUntil, 'dateTime') }) : t('validUntil', { when: date(q.validUntil, 'dateTime') })}</span> : null}
            <span className="nl-qt-muted">{t('versionTag', { version: q.version })}</span>
          </div>
          <h1 className="nl-qt-title">{page.title}</h1>
          <p className="nl-qt-muted">{t('quotedBy')} <Link to="/providers/$slug" params={{ slug: p.slug }}><strong>{p.name}</strong></Link> · {meta}</p>
          {q.state === 'superseded' ? (
            <p className="nl-qt-note" role="note">{t('revisedNote', { name: p.name, version: q.versions[0]?.version ?? q.version + 1 })} <Link to="/quotes/$quoteId" params={{ quoteId: q.currentQuoteId }}>{t('seeNewest', { version: q.versions[0]?.version ?? q.version + 1 })}</Link></p>
          ) : null}

          <h2 className="nl-qt-h2">{t('included')}</h2>
          <div className="nl-qt-tablewrap">
            <table className="table nl-qt-lines">
              <thead><tr><th scope="col">{t('thItem')}</th><th scope="col">{t('thQty')}</th><th scope="col" className="nl-qt-num">{t('thPrice')}</th></tr></thead>
              <tbody>
                {q.lines.map((l, i) => (
                  <tr key={i}>
                    <td><span className="nl-qt-kind">{t(`kind_${l.kind}`)}</span> {l.description}{l.note ? <><br /><span className="nl-qt-muted">{l.note}</span></> : null}</td>
                    <td>{l.qty.toLocaleString(locale === 'fr' ? 'fr-CA' : 'en-CA')}</td>
                    <td className="nl-qt-num">{money(l.amountCents)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <dl className="nl-qt-totals">
            <div><dt>{t('subtotal')}</dt><dd>{money(q.subtotalCents)}</dd></div>
            <div><dt>{t('tax', { pct: pct(q.taxBps) })}</dt><dd>{money(q.taxCents)}</dd></div>
            <div className="nl-qt-total"><dt>{t('total')}</dt><dd>{money(q.totalCents)}</dd></div>
          </dl>

          <h2 className="nl-qt-h2">{t('scope')}</h2>
          <p className="nl-qt-text">{q.scope}</p>
          {q.exclusions ? <><h2 className="nl-qt-h2">{t('exclusions')}</h2><p className="nl-qt-text">{q.exclusions}</p></> : null}

          <h2 className="nl-qt-h2">{t('terms')}</h2>
          <ul className="nl-qt-terms">
            <li>{q.proposedAt ? t('whenProposed', { when: date(q.proposedAt, 'dateTime'), duration: hours || '—' }) : t('noTime')}</li>
            <li>{q.depositKind === 'pct' && q.depositBps
              ? t('depositTerm_pct', { pct: pct(q.depositBps), amount: money(q.depositCents), balance: money(balance) })
              : q.depositKind === 'parts_upfront' && q.depositCents > 0
                ? t('depositTerm_parts_upfront', { amount: money(q.depositCents), balance: money(balance) })
                : t('depositTerm_none', { amount: money(q.totalCents) })}</li>
            <li>{t(`warranty_${q.warranty}` as 'warranty_none')}</li>
            <li>{t('releasedAfter')}</li>
          </ul>

          {q.versions.length > 1 ? (
            <>
              <h2 className="nl-qt-h2">{t('versions')}</h2>
              <ol className="nl-qt-versions">
                {q.versions.map(v => (
                  <li key={v.quoteId}>
                    {v.quoteId === q.id
                      ? <span aria-current="page">{t('versionRow', { version: v.version, total: money(v.totalCents) })} · {t('thisVersion')}</span>
                      : <Link to="/quotes/$quoteId" params={{ quoteId: v.quoteId }}>{t('versionRow', { version: v.version, total: money(v.totalCents) })}</Link>}
                    {' '}<span className="nl-qt-muted">{t(`state_${v.state}` as 'state_sent')}</span>
                  </li>
                ))}
              </ol>
            </>
          ) : null}
        </div>
        <AcceptPanel page={page} />
      </div>
    </div>
  );
}

type Phase = { step: 'form' } | { step: 'stepUp'; mode: 'required' | 'enrol' } | { step: 'card'; acceptance: Acceptance } | { step: 'done'; booking: Confirmation };

function AcceptPanel({ page }: { page: Page }) {
  const t = useQuotesT();
  const { money } = useFormatters();
  const viewer = useViewer();
  const here = useLocation({ select: l => l.href });
  const qc = useQueryClient();
  const { quote: q, provider: p } = page;
  const [visit, setVisit] = useState<Visit>({ addressLine: '', unit: '', accessNote: '', contactPhone: '' });
  const [phase, setPhase] = useState<Phase>({ step: 'form' });
  const [error, setError] = useState<string>();
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const keys = useRef<{ body: string; key: string } | null>(null);
  const held = q.depositCents > 0 ? q.depositCents : q.totalCents;
  const open = !q.expired && (q.state === 'sent' || q.state === 'viewed');
  const refresh = () => qc.invalidateQueries({ queryKey: ['quotes'] });

  const fail = (e: unknown) => {
    if (e instanceof ValidationError) { setFieldErrors(e.byField()); setError(e.errors[0]?.message); return; }
    const code = e instanceof ApiError ? (e.body as { code?: string } | undefined)?.code : undefined;
    if (code === 'step_up_required' || code === 'second_factor_required') { setPhase({ step: 'stepUp', mode: code === 'step_up_required' ? 'required' : 'enrol' }); return; }
    const byCode: Record<string, string> = { quote_revised: t('revisedError'), quote_expired: t('expiredError'), slot_taken: t('notFreeError'), quote_no_time: t('noTimeError') };
    setError((code && byCode[code]) || t('genericError'));
    if (code === 'quote_revised' || code === 'quote_expired') void refresh();
  };
  const finish = async () => {
    try {
      const booking = await confirmAcceptance(q.id, clean(visit), `confirm-${q.id}`);
      setPhase({ step: 'done', booking });
      void refresh();
    } catch (e) { fail(e); }
  };
  const accept = async (proof?: string) => {
    setBusy(true); setError(undefined); setFieldErrors({});
    const body = clean(visit);
    const text = JSON.stringify(body);
    if (!keys.current || keys.current.body !== text) keys.current = { body: text, key: newIdempotencyKey() };
    try {
      const a = await acceptQuote(q.id, body, keys.current.key, proof);
      if (a.status === 'requires_action' || a.status === 'requires_payment_method') {
        if (a.clientSecret && a.publishableKey) setPhase({ step: 'card', acceptance: a });
        else setError(t('genericError'));
      } else await finish();
    } catch (e) { fail(e); } finally { setBusy(false); }
  };
  const decline = async () => {
    setBusy(true); setError(undefined);
    try { await declineQuote(q.id); await refresh(); } catch (e) { fail(e); } finally { setBusy(false); }
  };

  const others = page.others.length > 0 ? (
    <p className="nl-qt-muted nl-qt-others">
      {t('others', { count: page.others.length })} {page.others.map(o => `${o.providerName} ${money(o.totalCents, { whole: o.totalCents % 100 === 0 })}`).join(' · ')}.{' '}
      <Link to="/quotes/requests/$requestId" params={{ requestId: q.requestId }}>{t('compare')}</Link>
    </p>
  ) : null;

  if (phase.step === 'done' || q.state === 'accepted') {
    return (
      <aside className="nl-qt-aside" aria-label={t('acceptKicker')}>
        <div className="nl-qt-box-ok" role="status">
          {phase.step === 'done'
            ? t('acceptedBox', { amount: money(phase.booking.heldCents), ref: phase.booking.ref, name: p.name })
            : t('acceptedShort')}
        </div>
        <Link to="/account/orders" className="btn btn-primary nl-qt-wide">{t('seeBookings')}</Link>
      </aside>
    );
  }
  if (!open) {
    return (
      <aside className="nl-qt-aside" aria-label={t('acceptKicker')}>
        <div className="nl-qt-box-closed" role="status">
          {q.state === 'declined' ? t('declinedBox', { name: p.name }) : q.state === 'superseded' ? t('closedRevised') : t('closedExpired', { name: p.name })}
        </div>
        {others}
      </aside>
    );
  }
  return (
    <aside className="nl-qt-aside" aria-labelledby="qt-accept">
      <div id="qt-accept" className="nl-qt-kicker">{t('acceptKicker')}</div>
      <dl className="nl-qt-totals nl-qt-aside-sum">
        <div><dt>{q.depositCents > 0 ? t('depositNow') : t('heldNow')}</dt><dd>{money(held)}</dd></div>
        {q.depositCents > 0 ? <div><dt>{t('balanceLater')}</dt><dd>{money(q.totalCents - q.depositCents)}</dd></div> : null}
      </dl>
      <p className="nl-qt-muted">{t('bothHeld')}</p>
      {!viewer.loading && !viewer.user ? (
        <div className="nl-qt-signin"><span>{t('signInToAccept')}</span><a className="btn btn-primary" href={signInHref(here)}>{t('signIn')}</a></div>
      ) : (
        <>
          <fieldset className="nl-qt-visit" disabled={phase.step === 'card'}>
            <legend className="nl-qt-h3">{t('whereJob')}</legend>
            <Field label={t('address')} error={fieldErrors.addressLine}><TextInput value={visit.addressLine} maxLength={200} autoComplete="street-address" onChange={e => setVisit({ ...visit, addressLine: e.target.value })} /></Field>
            <Field label={t('unit')} error={fieldErrors.unit}><TextInput value={visit.unit} maxLength={40} onChange={e => setVisit({ ...visit, unit: e.target.value })} /></Field>
            <Field label={t('access')} hint={t('accessPrivate')} error={fieldErrors.accessNote}><TextArea value={visit.accessNote} rows={2} maxLength={500} onChange={e => setVisit({ ...visit, accessNote: e.target.value })} /></Field>
            <Field label={t('phone')} error={fieldErrors.contactPhone}><TextInput type="tel" value={visit.contactPhone} maxLength={20} autoComplete="tel" onChange={e => setVisit({ ...visit, contactPhone: e.target.value })} /></Field>
          </fieldset>
          {phase.step === 'card' ? (
            <StripeCard clientSecret={phase.acceptance.clientSecret!} publishableKey={phase.acceptance.publishableKey!} label={t('acceptCta', { amount: money(phase.acceptance.totalCents) })}
              onAuthorized={() => void finish()} onError={setError} />
          ) : (
            <button type="button" className="btn btn-primary nl-qt-wide nl-qt-primary" disabled={busy || !visit.addressLine?.trim()} aria-busy={busy} onClick={() => void accept()}>
              {busy ? t('accepting') : t('acceptCta', { amount: money(held) })}
            </button>
          )}
          <button type="button" className="btn btn-ghost nl-qt-wide" disabled={busy || phase.step === 'card'} onClick={() => void decline()}>{t('decline')}</button>
        </>
      )}
      {error ? <p className="nl-error" role="alert">{error}</p> : null}
      {others}
      {phase.step === 'stepUp' ? <StepUpDialog mode={phase.mode} onClose={() => setPhase({ step: 'form' })} onProof={proof => { setPhase({ step: 'form' }); void accept(proof); }} /> : null}
    </aside>
  );
}

const clean = (v: Visit): Visit => ({
  addressLine: v.addressLine?.trim() || undefined, unit: v.unit?.trim() || undefined,
  accessNote: v.accessNote?.trim() || undefined, contactPhone: v.contactPhone?.trim() || undefined,
});

export function QuoteSkeleton() {
  return (
    <div className="nl-page nl-qt" aria-busy="true">
      <Skeleton width={220} height={12} style={{ marginTop: 28 }} />
      <div className="nl-qt-split">
        <div className="nl-qt-main"><Skeleton width="70%" height={36} style={{ marginTop: 16 }} />{Array.from({ length: 5 }, (_, i) => <Skeleton key={i} height={36} style={{ marginTop: 8 }} />)}</div>
        <Skeleton height={320} radius="var(--radius-md)" />
      </div>
    </div>
  );
}

