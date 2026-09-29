import { useQuery } from '@tanstack/react-query';
import { Alert, EmptyState, ErrorState, PageSkeleton } from '@northline/ui';
import { storefrontQuery, useCreateStorefront } from '../storefront/api';
import { PageBuilder } from '../storefront/PageBuilder';
import { useStorefrontT } from '../storefront/messages';
import { useAdvanceStep, type Onboarding } from './api';
import { useOnboardingT } from './messages';

export interface PageStepProps { onboarding: Onboarding; onBack: () => void; onNext: () => void }

/** Step 5 · Business page / Store / Menu page (design 02 lines 198–249) — the shared page builder. */
export function PageStep({ onboarding: o, onBack, onNext }: PageStepProps) {
  const t = useStorefrontT();
  const ot = useOnboardingT();
  const query = useQuery(storefrontQuery(o.merchantId));
  const create = useCreateStorefront(o.merchantId);
  const advance = useAdvanceStep(o.merchantId);
  const s = query.data;

  return (
    <>
      <h1 className="nl-ob-title">{t(`title_${o.type}`)}</h1>
      <p className="nl-ob-intro" style={{ maxWidth: '60ch', marginBottom: 24 }}>
        {t(`intro_${o.type}`)} {t('introTail')} {s ? <LiveAt url={s.url} text={t('liveAt', { url: s.url })} /> : null}
      </p>
      {query.isPending ? <PageSkeleton kpis={0} rows={6} />
        : query.isError ? <ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />
        : !s ? <EmptyState action={<button type="button" className="btn btn-primary" disabled={create.isPending} onClick={() => create.mutate()}>{t('create')}</button>}>{t('empty')}</EmptyState>
        : <PageBuilder storefront={s} canEdit variant="onboarding" />}
      {advance.isError ? <div style={{ marginTop: 14 }}><Alert tone="error">{ot('saveError')}</Alert></div> : null}
      <div className="nl-ob-actions" style={{ marginTop: 28 }}>
        <button type="button" className="btn btn-primary" disabled={!s || advance.isPending} onClick={() => advance.mutate('listings', { onSuccess: onNext })}>{t(`save_${o.type}`)}</button>
        <button type="button" className="btn btn-ghost" onClick={onBack}>{ot('back')}</button>
      </div>
    </>
  );
}

/** "Live at northline.ca/<slug>, …" with the address in bold. */
function LiveAt({ url, text }: { url: string; text: string }) {
  const i = text.indexOf(url);
  if (i < 0) return <>{text}</>;
  return <>{text.slice(0, i)}<strong>{url}</strong>{text.slice(i + url.length)}</>;
}
