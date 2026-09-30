import { useMemo, useRef, useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Chip, DataTable, Field, OptionCard, PageSkeleton, Select, Skeleton, TextInput, useFormatters, useLocale, type DataTableColumn } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { attentionCount } from '../../lib/forms';
import { useMerchantId, useRole } from '../shell/api';
import {
  actionKey, downloads, payoutHistoryQuery, payoutOverviewQuery, schedulePreviewQuery, useConfirmBank, useInstantPayout, useLinkSession,
  usePrepareBank, useSaveSchedule, type Account, type Frequency, type MonthlyAnchor, type PayoutLine, type PayoutOverview, type Reserve, type Schedule,
} from './api';
import { dateTime, dayDate, timeOf, toCents, toDollars } from './format';
import { useFinanceT, type FinanceKey } from './messages';
import { QueryState } from './QueryState';
import { StepUpPanel } from './StepUpPanel';
import './finance.css';

type Panel = null | 'instant' | 'schedule' | 'bank';

/** Design 02 · Payouts: available now, instant payout, schedule, bank account change, tax documents, history. */
export function PayoutsScreen() {
  const merchantId = useMerchantId();
  const t = useFinanceT();
  const overview = useQuery(payoutOverviewQuery(merchantId));
  return (
    <>
      <span className="nl-kicker">{t('payoutsKicker')}</span>
      <h1 className="nl-page-title" style={{ margin: '0 0 24px' }}>{t('payoutsTitle')}</h1>
      <div className="fin-cols fin-cols-300">
        <div>
          <QueryState query={overview} skeleton={<PageSkeleton kpis={0} rows={4} />}>{o => <Money o={o} />}</QueryState>
          <TaxDocuments />
        </div>
        <div>
          <h3 className="fin-h3" style={{ marginTop: 0 }}>{t('payoutHistory')}</h3>
          <History />
        </div>
      </div>
    </>
  );
}

const bankName = (a: Account) => a.institutionName;

function Money({ o }: { o: PayoutOverview }) {
  const t = useFinanceT();
  const f = useFormatters();
  const { locale } = useLocale();
  const owner = useRole() === 'owner';
  const [panel, setPanel] = useState<Panel>(null);
  const account = o.account;
  const destination = account?.label ?? '';
  const line = o.pausedUntil ? t('pausedUntil', { date: dateTime(o.pausedUntil, locale) })
    : !account ? t('noAccount')
    : o.schedule.frequency === 'manual' || !o.nextPayoutAt ? t('manualTo', { account: destination })
    : t('scheduledTo', { date: dayDate(o.nextPayoutAt, locale), account: destination });
  const canInstant = owner && o.instant.eligible && !o.pausedUntil && !!account && o.payableCents >= o.instant.minAmountCents;
  const pending = o.pendingAccount;
  const ended = [pending, account].find(a => a?.disconnectedAt);
  return (
    <>
      <div className="fin-available">
        <div className="fin-available-k">{t('availableNow')}</div>
        <div className="fin-available-v">{f.money(o.availableCents)}</div>
        <div style={{ fontSize: 13, marginTop: 4 }}>{line}</div>
        {owner ? (
          <div className="fin-actions">
            <Button onClick={() => setPanel(p => (p === 'instant' ? null : 'instant'))} disabled={!canInstant} aria-expanded={panel === 'instant'}>{t('instantButton')}</Button>
            <Button variant="secondary" onClick={() => setPanel(p => (p === 'schedule' ? null : 'schedule'))} aria-expanded={panel === 'schedule'}>{t('changeSchedule')}</Button>
          </div>
        ) : null}
        {owner && !o.instant.eligible ? <div className="fin-small" style={{ marginTop: 8, color: 'inherit' }}>{t('ineligible')}</div> : null}
      </div>
      {panel === 'instant' && account ? <InstantPanel o={o} account={account} onClose={() => setPanel(null)} /> : null}
      {panel === 'schedule' ? <SchedulePanel o={o} onClose={() => setPanel(null)} /> : null}
      <h3 className="fin-h3">{t('bankTitle')}</h3>
      <div className="fin-rows">
        <div className="fin-row fin-row-44">
          <span>{pending?.effectiveAt
            ? t('bankPendingLabel', { bank: bankName(pending), last4: pending.last4, holder: pending.holderName, date: dateTime(pending.effectiveAt, locale) })
            : account ? t('bankLabel', { bank: bankName(account), last4: account.last4, holder: account.holderName }) : t('noAccount')}</span>
          {owner ? <Button variant="ghost" onClick={() => setPanel(p => (p === 'bank' ? null : 'bank'))} aria-expanded={panel === 'bank'}>{t('change')}</Button> : null}
        </div>
        {ended?.disconnectedAt ? (
          <div className="fin-row fin-row-44" role="status">
            <span className="fin-small">{t('connectionEnded', { date: dateTime(ended.disconnectedAt, locale) })}</span>
            {owner && panel !== 'bank' ? <Button variant="ghost" onClick={() => setPanel('bank')}>{t('reconnect')}</Button> : null}
          </div>
        ) : null}
      </div>
      {panel === 'bank' ? <BankPanel o={o} onClose={() => setPanel(null)} /> : null}
    </>
  );
}

// ── instant payout ────────────────────────────────────────────────────────────────────────────────────────────────

export function instantAmountError(input: string, maxCents: number, t: ReturnType<typeof useFinanceT>, money: (c: number) => string): string | undefined {
  if (!input.trim()) return t('amountRequired');
  const cents = toCents(input);
  if (!Number.isFinite(cents) || cents < 100) return t('amountMin');
  if (cents > maxCents) return t('amountMax', { amount: money(maxCents) });
  return undefined;
}
const instantFee = (cents: number) => Math.max(50, Math.round(cents / 100));

function InstantPanel({ o, account, onClose }: { o: PayoutOverview; account: Account; onClose: () => void }) {
  const t = useFinanceT();
  const f = useFormatters();
  const { locale } = useLocale();
  const merchantId = useMerchantId();
  const payout = useInstantPayout(merchantId);
  const key = useRef(actionKey());
  const [amount, setAmount] = useState(toDollars(o.payableCents));
  const [touched, setTouched] = useState(false);
  const [step, setStep] = useState<'amount' | 'confirm' | 'done'>('amount');
  const [serverError, setServerError] = useState<string>();
  const error = instantAmountError(amount, o.payableCents, t, c => f.money(c)) ?? serverError;
  const cents = toCents(amount);
  const fee = Number.isFinite(cents) ? instantFee(cents) : 0;
  const net = Number.isFinite(cents) ? Math.max(0, cents - fee) : 0;
  const result = payout.data;
  const eta = result ? (new Date(result.arrivesAt).toDateString() === new Date().toDateString() ? `${timeOf(result.arrivesAt, locale)}` : dateTime(result.arrivesAt, locale)) : '';

  function next(e: FormEvent) {
    e.preventDefault();
    setTouched(true);
    if (!instantAmountError(amount, o.payableCents, t, c => f.money(c))) { key.current.renew(); setStep('confirm'); }
  }
  async function confirm(proof: string) {
    try {
      await payout.mutateAsync({ amountCents: cents, key: key.current.get(), proof });
      setStep('done');
    } catch (e) {
      if (e instanceof ValidationError) { setServerError(e.byField().amountCents); setStep('amount'); return; }
      throw e;
    }
  }
  return (
    <section className="fin-box" aria-label={t('instantTitle')}>
      <div className="fin-box-k">{t('instantTitle')}</div>
      {step === 'amount' ? (
        <form onSubmit={next} noValidate>
          <Field label={t('amount')} error={touched ? error : undefined} className="fin-field" >
            <TextInput inputMode="decimal" value={amount} onBlur={() => setTouched(true)} onChange={e => { setAmount(e.target.value.replace(/[^\d.,]/g, '')); setServerError(undefined); }} style={{ maxWidth: 160 }} />
          </Field>
          <Button type="button" variant="ghost" onClick={() => { setAmount(toDollars(o.payableCents)); setServerError(undefined); }}>{t('max', { amount: f.money(o.payableCents) })}</Button>
          <div className="fin-rows" style={{ marginTop: 10, gap: 4 }}>
            <div className="fin-row"><span>{t('feeLine')}</span><span>−{f.money(fee)}</span></div>
            <div className="fin-row" style={{ fontWeight: 600 }}><span>{t('arrivesLine', { account: account.label })}</span><span>{f.money(net)}</span></div>
          </div>
          <div className="fin-small" style={{ marginTop: 8 }}>{o.schedule.frequency === 'weekly' && o.schedule.weekday === 5 ? t('instantNote') : t('instantNoteGeneric')}</div>
          <div className="fin-actions">
            <Button type="submit" disabled={!!error}>{t('continue')}</Button>
            <Button type="button" variant="ghost" onClick={onClose}>{t('cancel')}</Button>
          </div>
        </form>
      ) : step === 'confirm' ? (
        <StepUpPanel
          intro={<div className="fin-note"><strong>{t('stepUpPayout')}</strong> {t('stepUpPayoutMore')}</div>}
          confirmLabel={t('usePasskey')} onProof={confirm} onCancel={onClose} busy={payout.isPending}
        />
      ) : (
        <>
          <div className="fin-note" role="status"><strong>{t('onItsWay', { amount: f.money(result?.netCents ?? net) })}</strong> {t('onItsWayMore', { ref: result?.reference ?? '', account: result?.destination ?? account.label, eta })}</div>
          <Button variant="ghost" onClick={onClose} style={{ marginTop: 8 }}>{t('done')}</Button>
        </>
      )}
    </section>
  );
}

// ── schedule ──────────────────────────────────────────────────────────────────────────────────────────────────────

const FREQS: Frequency[] = ['weekly', 'daily', 'monthly', 'manual'];

function SchedulePanel({ o, onClose }: { o: PayoutOverview; onClose: () => void }) {
  const t = useFinanceT();
  const f = useFormatters();
  const { locale } = useLocale();
  const merchantId = useMerchantId();
  const save = useSaveSchedule(merchantId);
  const [frequency, setFrequency] = useState<Frequency>(o.schedule.frequency);
  const [weekday, setWeekday] = useState<number>(o.schedule.weekday ?? 5);
  const [anchor, setAnchor] = useState<MonthlyAnchor>(o.schedule.monthlyAnchor ?? 'first');
  const [reserve, setReserve] = useState<Reserve>(o.schedule.reserve);
  const [saved, setSaved] = useState(false);
  const schedule: Schedule = { frequency, reserve, weekday: frequency === 'weekly' ? weekday : null, monthlyAnchor: frequency === 'monthly' ? anchor : null };
  const preview = useQuery(schedulePreviewQuery(merchantId, schedule));
  const change = (fn: () => void) => { fn(); setSaved(false); save.reset(); };
  const next = preview.data?.nextPayoutAt;
  const amount = f.money(preview.data?.amountCents ?? o.payableCents);
  const text = saved
    ? (frequency === 'manual' || !next ? t('previewSavedManual') : t('previewSaved', { date: dayDate(next, locale) }))
    : frequency === 'manual' ? t('previewManual')
    : !next ? ''
    : frequency === 'weekly' ? t('previewWeekly', { date: dayDate(next, locale), amount })
    : frequency === 'daily' ? t('previewDaily', { date: dayDate(next, locale), amount })
    : t('previewMonthly', { date: f.date(next) });
  const error = save.error instanceof ValidationError ? Object.values(save.error.byField())[0] : save.error ? t('loadError') : undefined;
  return (
    <section className="fin-box" aria-label={t('scheduleTitle')}>
      <div className="fin-box-k">{t('scheduleTitle')}</div>
      <div className="fin-options" role="radiogroup" aria-label={t('scheduleTitle')}>
        {FREQS.map(fr => (
          <OptionCard key={fr} role="radio" aria-checked={frequency === fr} selected={frequency === fr} onClick={() => change(() => setFrequency(fr))}
            title={t(`f_${fr}` as FinanceKey)} description={t(`f_${fr}_desc` as FinanceKey)}
            trailing={<span style={{ fontSize: 13, whiteSpace: 'nowrap' }}>{t(`f_${fr}_fee` as FinanceKey)}</span>} />
        ))}
      </div>
      {frequency === 'weekly' ? (
        <div className="nl-field" style={{ marginTop: 10 }}>
          <span className="nl-label" id="fin-dow">{t('dayOfWeek')}</span>
          <div className="fin-days" role="radiogroup" aria-labelledby="fin-dow">
            {[1, 2, 3, 4, 5].map(d => <Chip key={d} role="radio" aria-checked={weekday === d} selected={weekday === d} onClick={() => change(() => setWeekday(d))}>{t(`d${d}` as FinanceKey)}</Chip>)}
          </div>
        </div>
      ) : null}
      {frequency === 'monthly' ? (
        <div style={{ marginTop: 10 }}>
          <Field label={t('dayOfMonth')}>
            <Select value={anchor} onChange={e => change(() => setAnchor(e.target.value as MonthlyAnchor))} style={{ maxWidth: 160 }}
              options={(['first', 'fifteenth', 'last'] as const).map(a => ({ value: a, label: t(`m_${a}`) }))} />
          </Field>
        </div>
      ) : null}
      <div style={{ marginTop: 10 }}>
        <Field label={t('reserveLabel')} hint={t('reserveHint')}>
          <Select value={reserve} onChange={e => change(() => setReserve(e.target.value as Reserve))} style={{ maxWidth: 240 }}
            options={(['none', 'keep_500', 'percent_10'] as const).map(r => ({ value: r, label: t(`r_${r}`) }))} />
        </Field>
      </div>
      <div className="fin-muted" style={{ marginTop: 10 }} aria-live="polite">{preview.isPending && !saved ? <Skeleton width={260} /> : text}</div>
      {error ? <div role="alert" className="nl-error">{error}</div> : null}
      <div className="fin-actions">
        <Button onClick={() => save.mutate(schedule, { onSuccess: () => setSaved(true) })} disabled={save.isPending || saved} aria-busy={save.isPending}>
          {save.isPending ? t('saving') : saved ? t('saved') : t('saveSchedule')}
        </Button>
        <Button variant="ghost" onClick={onClose}>{t('cancel')}</Button>
      </div>
    </section>
  );
}

// ── bank account ──────────────────────────────────────────────────────────────────────────────────────────────────

interface BankFields { institution: string; transit: string; accountNumber: string; holderName: string }
const BANK_FIELDS = ['institution', 'transit', 'accountNumber', 'holderName'] as const;

export function bankErrors(v: BankFields, t: ReturnType<typeof useFinanceT>): Partial<Record<keyof BankFields, string>> {
  return {
    institution: /^\d{3}$/.test(v.institution.trim()) ? undefined : t('institutionFormat'),
    transit: /^\d{5}$/.test(v.transit.trim()) ? undefined : t('transitFormat'),
    accountNumber: /^\d{7,12}$/.test(v.accountNumber.trim()) ? undefined : t('accountFormat'),
    holderName: !v.holderName.trim() ? t('holderRequired') : undefined,
  };
}

interface StripeJs {
  collectBankAccountToken: (o: { clientSecret: string }) => Promise<{ token?: { id: string }; financialConnectionsAccount?: { id: string }; error?: { message: string } }>;
}
interface BankLink { token: string; financialConnectionsAccount?: string }

/** Stripe.js is loaded only here, only when the api hands out a real Financial Connections session (S-24). */
async function stripeBankLink(publishableKey: string, clientSecret: string): Promise<BankLink | undefined> {
  const w = window as unknown as { Stripe?: (k: string) => StripeJs };
  if (!w.Stripe) {
    await new Promise<void>((resolve, reject) => {
      const s = document.createElement('script');
      s.src = 'https://js.stripe.com/v3';
      s.onload = () => resolve();
      s.onerror = () => reject(new Error('Stripe.js failed to load'));
      document.head.appendChild(s);
    });
  }
  const result = await w.Stripe!(publishableKey).collectBankAccountToken({ clientSecret });
  return result.token ? { token: result.token.id, financialConnectionsAccount: result.financialConnectionsAccount?.id } : undefined;
}

/**
 * Without Stripe (local profile, no key) the api's session is `fake`: this stands in for the Financial Connections
 * modal — pick a bank, get a local token the fake adapter understands. Stripe.js is never loaded.
 */
const FAKE_BANKS = [['003', '8820', 'RBC'], ['004', '3391', 'TD Canada Trust'], ['001', '4417', 'BMO'], ['002', '2208', 'Scotiabank'], ['010', '6631', 'CIBC'], ['219', '5102', 'ATB Financial'], ['815', '9034', 'Desjardins']] as const;

function FakeBankPicker({ busy, onLink, onCancel }: { busy: boolean; onLink: (link: BankLink) => void; onCancel: () => void }) {
  const t = useFinanceT();
  const [choice, setChoice] = useState(0);
  const [inst, last4] = FAKE_BANKS[choice]!;
  return (
    <div className="fin-dashed" role="group" aria-label={t('fakeTitle')}>
      <strong>{t('fakeTitle')}</strong>
      <div className="fin-small" style={{ marginTop: 4 }}>{t('fakeText')}</div>
      <div style={{ marginTop: 10, textAlign: 'left' }}>
        <Field label={t('fakeBank')}>
          <Select value={String(choice)} onChange={e => setChoice(Number(e.target.value))}
            options={FAKE_BANKS.map(([, l4, name], i) => ({ value: String(i), label: `${name} ··${l4}` }))} />
        </Field>
      </div>
      <div className="fin-actions">
        <Button type="button" disabled={busy} aria-busy={busy}
          onClick={() => onLink({ token: `btok_local_${inst}_${last4}`, financialConnectionsAccount: `fca_local_${Date.now().toString(36)}` })}>
          {busy ? t('working') : t('fakeLink')}
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>{t('fakeCancel')}</Button>
      </div>
    </div>
  );
}

function BankPanel({ o, onClose }: { o: PayoutOverview; onClose: () => void }) {
  const t = useFinanceT();
  const { locale } = useLocale();
  const merchantId = useMerchantId();
  const link = useLinkSession(merchantId);
  const prepare = usePrepareBank(merchantId);
  const confirm = useConfirmBank(merchantId);
  const key = useRef(actionKey());
  const [mode, setMode] = useState<'instant' | 'manual'>('instant');
  const [step, setStep] = useState<'form' | 'confirm' | 'done'>('form');
  const [draft, setDraft] = useState<Account>();
  const [values, setValues] = useState<BankFields>({ institution: '', transit: '', accountNumber: '', holderName: o.account?.holderName ?? '' });
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [submitted, setSubmitted] = useState(false);
  const [server, setServer] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string>();
  const [picking, setPicking] = useState(false);
  const clientErrors = mode === 'manual' ? bankErrors(values, t) : {};
  const shown = Object.fromEntries(BANK_FIELDS.map(k => [k, server[k] ?? ((touched[k] || submitted) ? clientErrors[k] : undefined)])) as Record<string, string | undefined>;
  const busy = link.isPending || prepare.isPending;

  async function next(e: FormEvent) {
    e.preventDefault();
    setSubmitted(true);
    setFormError(undefined);
    if (mode === 'manual' && attentionCount(clientErrors) > 0) return;
    try {
      if (mode === 'instant') {
        const session = await link.mutateAsync();
        if (session.mode !== 'stripe' || !session.clientSecret || !session.publishableKey) { setPicking(true); return; }
        const linked = await stripeBankLink(session.publishableKey, session.clientSecret);
        if (!linked) { setFormError(t('linkCancelled')); return; }
        await linkAccount(linked);
      } else {
        prepared(await prepare.mutateAsync({ method: 'manual', ...Object.fromEntries(BANK_FIELDS.map(k => [k, values[k].trim()])) }));
      }
    } catch (err) {
      failed(err);
    }
  }
  function prepared(account: Account) {
    setDraft(account);
    setPicking(false);
    key.current.renew();
    setStep('confirm');
  }
  async function linkAccount(linked: BankLink) {
    prepared(await prepare.mutateAsync({ method: 'instant', linkedAccount: linked.token, financialConnectionsAccount: linked.financialConnectionsAccount, holderName: values.holderName || undefined }));
  }
  function failed(err: unknown) {
    if (err instanceof ValidationError) {
      const byField = err.byField();
      setServer(byField);
      const other = Object.entries(byField).filter(([k]) => !(BANK_FIELDS as readonly string[]).includes(k)).map(([, m]) => m);
      if (other.length) setFormError(other[0]);
    } else setFormError(t('loadError'));
  }
  const set = (k: keyof BankFields) => (e: { target: { value: string } }) => { setValues(v => ({ ...v, [k]: e.target.value })); setServer(s => ({ ...s, [k]: undefined as unknown as string })); };
  const attention = submitted ? attentionCount(shown) : 0;
  return (
    <section className="fin-box" aria-label={t('bankPanelTitle')}>
      <div className="fin-box-k">{t('bankPanelTitle')}</div>
      <div className="fin-warn"><strong>{t('security')}</strong> {t('securityText')}</div>
      {step === 'form' ? (
        <form onSubmit={next} noValidate>
          <div className="fin-days" style={{ marginTop: 12 }} role="radiogroup" aria-label={t('bankPanelTitle')}>
            <Chip role="radio" aria-checked={mode === 'instant'} selected={mode === 'instant'} onClick={() => setMode('instant')}>{t('connectInstantly')}</Chip>
            <Chip role="radio" aria-checked={mode === 'manual'} selected={mode === 'manual'} onClick={() => { setMode('manual'); setPicking(false); }}>{t('enterManually')}</Chip>
          </div>
          {mode === 'instant' ? (picking
            ? <FakeBankPicker busy={busy} onCancel={() => setPicking(false)} onLink={l => { setFormError(undefined); linkAccount(l).catch(failed); }} />
            : <div className="fin-dashed">{t('fcText')}</div>) : (
            <>
              {attention > 0 ? <div role="alert" className="nl-error" style={{ marginTop: 12 }}>{t('attention', { count: attention })}</div> : null}
              <div className="fin-bank-fields">
                <Field label={t('institution')} error={shown.institution}><TextInput inputMode="numeric" placeholder="004" value={values.institution} onChange={set('institution')} onBlur={() => setTouched(x => ({ ...x, institution: true }))} /></Field>
                <Field label={t('transit')} error={shown.transit}><TextInput inputMode="numeric" placeholder="12345" value={values.transit} onChange={set('transit')} onBlur={() => setTouched(x => ({ ...x, transit: true }))} /></Field>
                <Field label={t('accountNumber')} error={shown.accountNumber}><TextInput inputMode="numeric" autoComplete="off" placeholder={t('accountPlaceholder')} value={values.accountNumber} onChange={set('accountNumber')} onBlur={() => setTouched(x => ({ ...x, accountNumber: true }))} /></Field>
                <Field label={t('holder')} error={shown.holderName}><TextInput value={values.holderName} onChange={set('holderName')} onBlur={() => setTouched(x => ({ ...x, holderName: true }))} /></Field>
              </div>
              <div className="fin-small" style={{ marginTop: 6 }}>{t('manualNote')}</div>
            </>
          )}
          {formError ? <div role="alert" className="nl-error" style={{ marginTop: 10 }}>{formError}</div> : null}
          {picking ? null : (
            <div className="fin-actions">
              <Button type="submit" disabled={busy} aria-busy={busy}>{busy ? t('working') : t('continue')}</Button>
              <Button type="button" variant="ghost" onClick={onClose}>{t('cancel')}</Button>
            </div>
          )}
        </form>
      ) : step === 'confirm' && draft ? (
        <>
          <div style={{ marginTop: 12, fontSize: 14 }}>{t('newAccount')} <strong>{draft.institutionName} · ··{draft.last4} · {draft.holderName}</strong> · {t('verifiedVia')}</div>
          <StepUpPanel confirmLabel={t('confirmWithPasskey')} onCancel={onClose} busy={confirm.isPending}
            onProof={async proof => { await confirm.mutateAsync({ accountId: draft.id, key: key.current.get(), proof }); setStep('done'); }} />
        </>
      ) : (
        <>
          <div className="fin-note" role="status" style={{ marginTop: 12 }}>
            <strong>{t('accountChanged')}</strong> {t('accountChangedMore', { date: confirm.data?.effectiveAt ? dateTime(confirm.data.effectiveAt, locale) : '', account: draft?.label ?? '' })}
          </div>
          <Button variant="ghost" onClick={onClose} style={{ marginTop: 8 }}>{t('done')}</Button>
        </>
      )}
    </section>
  );
}

// ── tax documents + history ───────────────────────────────────────────────────────────────────────────────────────

function TaxDocuments() {
  const t = useFinanceT();
  const merchantId = useMerchantId();
  const year = new Date().getFullYear();
  return (
    <>
      <h3 className="fin-h3">{t('taxDocuments')}</h3>
      <div className="fin-rows">
        <div className="fin-row fin-row-40"><span>{t('gstYtd', { year })}</span><a href={downloads.gst(merchantId, year)} download>{t('download')}</a></div>
        <div className="fin-row fin-row-40"><span>{t('annualStatement', { year: year - 1 })}</span><a href={downloads.annual(merchantId, year - 1)} download>{t('download')}</a></div>
      </div>
    </>
  );
}

interface HistoryRow { id: string; date: string; amount: number; jobs: number; status: string; tone: 'tag-accent' | 'tag-accent-2' | 'tag-neutral' }

export function historyRows(lines: PayoutLine[], t: ReturnType<typeof useFinanceT>, date: (iso: string) => string): HistoryRow[] {
  return lines.map(p => ({
    id: p.id, date: p.kind === 'instant' ? `${date(p.createdAt)} · ${t('instantTag')}` : date(p.createdAt), amount: p.amountCents, jobs: p.itemCount,
    status: t(`ps_${p.state}` as FinanceKey), tone: p.state === 'paid' ? 'tag-accent' : p.state === 'failed' ? 'tag-accent-2' : 'tag-neutral',
  }));
}

function History() {
  const t = useFinanceT();
  const f = useFormatters();
  const merchantId = useMerchantId();
  const role = useRole();
  const history = useQuery(payoutHistoryQuery(merchantId));
  const rows = useMemo(() => historyRows(history.data ?? [], t, iso => f.date(iso)), [history.data, t, f]);
  const columns: DataTableColumn<HistoryRow>[] = [
    { key: 'date', label: t('colDate'), primary: true },
    { key: 'amount', label: t('colAmount'), type: 'money' },
    { key: 'jobs', label: t('colJobs'), type: 'num', priority: 1 },
    { key: 'status', label: t('colStatus'), type: 'tag' },
  ];
  if (history.isPending) return <Skeleton height={300} />;
  return (
    <DataTable<HistoryRow> entity={t('payoutEntity')} plural={t('payoutPlural')} columns={columns} rows={rows} pageSize={10}
      rowTones={r => ({ status: r.tone })} can={{ create: false, update: false, delete: false, export: true }}
      roleName={t(`role_${role}` as FinanceKey)} emptyText={t('payoutsEmpty')} reportName="payouts"
      error={history.isError ? t('loadError') : null} onRetry={() => void history.refetch()} />
  );
}
