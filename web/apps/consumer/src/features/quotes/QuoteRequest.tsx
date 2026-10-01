import { useCallback, useEffect, useState } from 'react';
import { useQuery, useSuspenseQuery, type UseQueryResult } from '@tanstack/react-query';
import { Link, useLocation } from '@tanstack/react-router';
import { ValidationError } from '@northline/client';
import { BrandMark, EmptyState, ErrorState, Field, Skeleton, TextArea, TextInput, useLocale } from '@northline/ui';
import { useBookingT } from '../booking/messages';
import { useDeliveryLocation } from '../location/useDeliveryLocation';
import { signInHref, useViewer } from '../session/api';
import { providersQuery, serviceCategoryQuery, type Providers } from '../services/api';
import { percent, rating } from '../services/format';
import { placeOf } from '../services/ProviderList';
import { categoryName } from '../services/taxonomy';
import { requestQuotes, type QuoteAsk } from './api';
import { useQuotesT } from './messages';

export type RequestStep = 'job' | 'where' | 'who';
export const REQUEST_STEPS: readonly RequestStep[] = ['job', 'where', 'who'];
const MAX = 3;

export interface RequestDraft {
  description: string;
  vehicle: { year: string; make: string; model: string };
  eventDate: string;
  guests: string;
  budget: string;
  preferredDate: string;
  note: string;
  providers: string[];
}

const EMPTY: RequestDraft = { description: '', vehicle: { year: '', make: '', model: '' }, eventDate: '', guests: '', budget: '', preferredDate: '', note: '', providers: [] };
const storeKey = (slug: string) => `nl.quote.${slug}`;

/** The request survives a reload and the trip to the sign-in page (sessionStorage, per category), until sent. */
function useRequestDraft(slug: string, preselect?: string) {
  const [draft, setDraft] = useState<RequestDraft>(() => ({ ...EMPTY, providers: preselect ? [preselect] : [] }));
  useEffect(() => {
    try {
      const raw = sessionStorage.getItem(storeKey(slug));
      if (raw) {
        const saved = { ...EMPTY, ...(JSON.parse(raw) as Partial<RequestDraft>) };
        setDraft(preselect && !saved.providers.includes(preselect) && saved.providers.length < MAX ? { ...saved, providers: [...saved.providers, preselect] } : saved);
      }
    } catch { /* private mode: keep it in memory */ }
  }, [slug, preselect]);
  const update = useCallback((patch: Partial<RequestDraft>) => setDraft(prev => {
    const next = { ...prev, ...patch };
    try { sessionStorage.setItem(storeKey(slug), JSON.stringify(next)); } catch { /* ignore */ }
    return next;
  }), [slug]);
  const clear = useCallback(() => { try { sessionStorage.removeItem(storeKey(slug)); } catch { /* ignore */ } }, [slug]);
  return { draft, update, clear };
}

/** Whether a step's answers are in. */
export function requestComplete(step: RequestStep, d: RequestDraft, kind: string, vehicle: boolean): boolean {
  if (step === 'job') {
    if (d.description.trim().length < 10) return false;
    if (vehicle && !(d.vehicle.year && d.vehicle.make && d.vehicle.model.trim())) return false;
    if (kind === 'event' && !(d.eventDate && Number(d.guests) >= 1)) return false;
    return true;
  }
  if (step === 'who') return d.providers.length >= 1 && d.providers.length <= MAX;
  return true;
}

/** The request body (POST /api/v1/me/quote-requests). */
export function toAsk(d: RequestDraft, category: string, kind: string, vehicle: boolean, area: string | undefined): QuoteAsk {
  return {
    category, providers: d.providers, description: d.description.trim(),
    vehicle: vehicle ? { year: d.vehicle.year, make: d.vehicle.make, model: d.vehicle.model.trim() } : undefined,
    eventDate: kind === 'event' ? d.eventDate || undefined : undefined,
    guests: kind === 'event' && d.guests ? Number(d.guests) : undefined,
    budget: d.budget.trim() || undefined, note: d.note.trim() || undefined, area: area || undefined,
    preferredDate: kind !== 'event' ? d.preferredDate || undefined : undefined,
  };
}

/** design 06 `book` in quote mode: the job → where & when → who should quote → sent (the compare page). */
export function QuoteRequest({ slug, step, preselect, onStep, onSent }: {
  slug: string; step: RequestStep; preselect?: string; onStep: (step: RequestStep) => void; onSent: (requestId: string) => void;
}) {
  const t = useQuotesT();
  const b = useBookingT();
  const { locale } = useLocale();
  const { data: category } = useSuspenseQuery(serviceCategoryQuery(slug, locale));
  const { draft, update, clear } = useRequestDraft(slug, preselect);
  const { location } = useDeliveryLocation();
  const place = placeOf(location);
  const list = useQuery({ ...providersQuery(slug, place ?? {}, locale), enabled: place !== null });
  const area = list.data?.area ?? list.data?.city ?? location.city ?? undefined;
  const name = categoryName(category.slug, category.names, locale);
  const idx = REQUEST_STEPS.indexOf(step);
  const go = (i: number) => onStep(REQUEST_STEPS[Math.max(0, Math.min(REQUEST_STEPS.length - 1, i))]!);

  if (!category.quoteable) {
    return (
      <div className="nl-page nl-qt">
        <EmptyState action={<Link to="/services/$category/providers" params={{ category: slug }} className="btn btn-secondary">{name.text}</Link>}>{t('loadError')}</EmptyState>
      </div>
    );
  }

  return (
    <div className="nl-page nl-qt">
      <nav aria-label={t('breadcrumb')} className="nl-qt-crumbs">
        <Link to="/services">{t('crumbServices')}</Link> › <Link to="/services/$category" params={{ category: slug }} lang={name.lang}>{name.text}</Link> › <span aria-current="page">{t('crumbRequest')}</span>
      </nav>
      <ol className="nl-qt-steps" aria-label={t('steps')}>
        {REQUEST_STEPS.map((s, i) => (
          <li key={s}>
            <button type="button" className="nl-qt-step" data-state={i < idx ? 'done' : i === idx ? 'current' : 'todo'} aria-current={i === idx ? 'step' : undefined} disabled={i > idx} onClick={() => onStep(s)}>
              <span className="nl-qt-step-n" aria-hidden>{i + 1}</span>{t(`step_${s}`)}
            </button>
          </li>
        ))}
      </ol>
      {step === 'job' ? <Job kind={category.kind} vehicle={category.vehicle} draft={draft} update={update} onNext={() => go(1)} /> : null}
      {step === 'where' ? (
        <section aria-labelledby="qt-where">
          <h1 id="qt-where" className="nl-qt-title">{t('whereTitle')}</h1>
          <p className="nl-qt-lede">{t('whereLede')}</p>
          <Field label={t('area')} hint={t('areaHint')}><TextInput value={area ?? ''} readOnly /></Field>
          {category.kind !== 'event' ? (
            <Field label={t('preferredDate')}><TextInput type="date" value={draft.preferredDate} onChange={e => update({ preferredDate: e.target.value })} /></Field>
          ) : null}
          <div className="nl-qt-actions">
            <button type="button" className="btn btn-primary nl-qt-primary" onClick={() => go(2)}>{t('whereCta')}</button>
            <button type="button" className="btn btn-ghost" onClick={() => go(0)}>{b('back')}</button>
          </div>
        </section>
      ) : null}
      {step === 'who' ? (
        <Who slug={slug} providers={list} areaLabel={area ?? ''} draft={draft} update={update} onBack={() => go(1)}
          send={() => requestQuotes(toAsk(draft, slug, category.kind, category.vehicle, area)).then(r => { clear(); onSent(r.requestId); })} />
      ) : null}
    </div>
  );
}

function Job({ kind, vehicle, draft, update, onNext }: { kind: string; vehicle: boolean; draft: RequestDraft; update: (p: Partial<RequestDraft>) => void; onNext: () => void }) {
  const t = useQuotesT();
  const b = useBookingT();
  const ok = requestComplete('job', draft, kind, vehicle);
  const years = ['2026', '2025', '2024', '2023', '2022', '2021', '2020', '2019', '2018', '2017', '2016', '2015', b('older')];
  const makes = ['Honda', 'Toyota', 'Ford', 'Chevrolet', 'Hyundai', 'Kia', 'Subaru', 'Tesla', b('other')];
  const hint = ok ? '' : kind === 'event' ? t('jobHint_event') : vehicle ? t('jobHint_vehicle') : t('jobHint');
  return (
    <section aria-labelledby="qt-job">
      <h1 id="qt-job" className="nl-qt-title">{t('jobTitle')}</h1>
      <p className="nl-qt-lede">{t('jobLede')}</p>
      <Field label={t('describe', { count: draft.description.length })}>
        <TextArea value={draft.description} maxLength={1000} rows={4} placeholder={t('describePh')} onChange={e => update({ description: e.target.value.slice(0, 1000) })} />
      </Field>
      {vehicle ? (
        <section aria-labelledby="qt-vehicle" className="nl-qt-section">
          <h2 id="qt-vehicle" className="nl-qt-h2">{b('vehicle')}</h2>
          <div className="nl-qt-grid">
            <Field label={b('year')}><select className="input" value={draft.vehicle.year} onChange={e => update({ vehicle: { ...draft.vehicle, year: e.target.value } })}><option value="">{b('select')}</option>{years.map(y => <option key={y}>{y}</option>)}</select></Field>
            <Field label={b('make')}><select className="input" value={draft.vehicle.make} onChange={e => update({ vehicle: { ...draft.vehicle, make: e.target.value } })}><option value="">{b('select')}</option>{makes.map(m => <option key={m}>{m}</option>)}</select></Field>
            <Field label={b('model')}><TextInput value={draft.vehicle.model} placeholder={b('modelPh')} maxLength={80} onChange={e => update({ vehicle: { ...draft.vehicle, model: e.target.value } })} /></Field>
          </div>
        </section>
      ) : null}
      {kind === 'event' ? (
        <div className="nl-qt-grid">
          <Field label={t('eventDate')}><TextInput type="date" value={draft.eventDate} onChange={e => update({ eventDate: e.target.value })} /></Field>
          <Field label={t('guests')}><TextInput type="number" inputMode="numeric" min={1} max={2000} value={draft.guests} onChange={e => update({ guests: e.target.value })} /></Field>
        </div>
      ) : null}
      <Field label={t('budget')}><TextInput value={draft.budget} placeholder={t('budgetPh')} maxLength={80} onChange={e => update({ budget: e.target.value })} /></Field>
      <div className="nl-qt-actions">
        <button type="button" className="btn btn-primary nl-qt-primary" disabled={!ok} onClick={onNext}>{t('jobCta')}</button>
        {hint ? <span className="nl-qt-hint" aria-live="polite">{hint}</span> : null}
      </div>
    </section>
  );
}

function Who({ slug, providers, areaLabel, draft, update, send, onBack }: {
  slug: string; providers: UseQueryResult<Providers>; areaLabel: string;
  draft: RequestDraft; update: (p: Partial<RequestDraft>) => void; send: () => Promise<void>; onBack: () => void;
}) {
  const t = useQuotesT();
  const b = useBookingT();
  const { locale } = useLocale();
  const viewer = useViewer();
  const here = useLocation({ select: l => l.href });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const items = providers.data?.items ?? [];
  const toggle = (s: string) => update({ providers: draft.providers.includes(s) ? draft.providers.filter(x => x !== s) : draft.providers.length >= MAX ? draft.providers : [...draft.providers, s] });
  const submit = async () => {
    setBusy(true); setError(undefined);
    try { await send(); } catch (e) { setError(e instanceof ValidationError ? e.errors[0]?.message : t('genericError')); } finally { setBusy(false); }
  };
  return (
    <section aria-labelledby="qt-who">
      <h1 id="qt-who" className="nl-qt-title">{t('whoTitle')}</h1>
      <p className="nl-qt-lede">{t('whoLede')}</p>
      <p className="nl-qt-muted" aria-live="polite">{t('whoCount', { count: draft.providers.length })}{draft.providers.length >= MAX ? ` · ${t('whoMax')}` : ''}</p>
      {providers.isPending ? <div aria-busy="true">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} height={64} style={{ marginTop: 8 }} />)}</div>
        : providers.isError ? <ErrorState message={t('providersError')} onRetry={() => void providers.refetch()} />
          : items.length === 0
            ? <EmptyState action={<Link to="/services/$category" params={{ category: slug }} className="btn btn-secondary">{t('back')}</Link>}>{t('noProviders', { area: areaLabel })}</EmptyState>
            : (
              <ul className="nl-qt-pick" aria-label={t('whoTitle')}>
                {items.map(p => {
                  const on = draft.providers.includes(p.slug);
                  const meta = p.reviewCount > 0
                    ? t('meta', { rating: rating(p.rating, locale), reviews: p.reviewCount, onTime: p.onTimePct != null ? percent(p.onTimePct, locale) : '—' })
                    : t('metaNew');
                  return (
                    <li key={p.merchantId}>
                      <button type="button" className="nl-qt-pick-row" aria-pressed={on} disabled={!on && draft.providers.length >= MAX} onClick={() => toggle(p.slug)}>
                        <span className="nl-qt-box" aria-hidden>{on ? '✓' : ''}</span>
                        <BrandMark name={p.name} color={p.brandColor} size={44} />
                        <span className="nl-qt-pick-text"><span className="nl-qt-pick-name">{p.name} <span className="tag nl-qt-tier">{t(`tier_${p.tier === 'master' || p.tier === 'trusted' ? p.tier : 'registered'}`)}</span></span><span className="nl-qt-muted">{meta}</span></span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
      <Field label={t('note')}><TextArea value={draft.note} rows={3} maxLength={500} placeholder={t('notePh')} onChange={e => update({ note: e.target.value })} /></Field>
      {error ? <p className="nl-error" role="alert">{error}</p> : null}
      {!viewer.loading && !viewer.user ? (
        <div className="nl-qt-signin">
          <span>{t('signInToSend')}</span>
          <a className="btn btn-primary" href={signInHref(here)}>{t('signIn')}</a>
          <a className="btn btn-secondary" href={signInHref(here, 'register')}>{t('createAccount')}</a>
        </div>
      ) : null}
      <div className="nl-qt-actions">
        <button type="button" className="btn btn-primary nl-qt-primary" disabled={busy || !viewer.user || draft.providers.length === 0} aria-busy={busy} onClick={() => void submit()}>
          {busy ? t('sending') : t('send', { count: draft.providers.length })}
        </button>
        <button type="button" className="btn btn-ghost" onClick={onBack}>{b('back')}</button>
      </div>
    </section>
  );
}
