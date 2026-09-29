import { useQuery } from '@tanstack/react-query';
import { Navigate, useNavigate } from '@tanstack/react-router';
import { ErrorState, PageSkeleton } from '@northline/ui';
import { useSession } from '../../lib/session';
import { businessesQuery, type MerchantType } from '../shell/api';
import { AccountStep } from './AccountStep';
import { onboardingQuery } from './api';
import { BusinessStep } from './BusinessStep';
import { ListingsStep } from './ListingsStep';
import { OnboardingLayout, reachable } from './OnboardingLayout';
import { PageStep } from './PageStep';
import { ReviewStep } from './ReviewStep';
import { VerificationStep } from './VerificationStep';
import { useOnboardingT } from './messages';
import { STEPS, studioHome, type OnboardingSearch, type Step } from './model';

/** Whether the signed-in user registered today (07d: brand-new account → Business Terms + all provinces). */
function useIsNewAccount(flag: boolean | undefined) {
  const { data } = useSession();
  if (flag) return true;
  const since = data?.user.memberSince;
  if (!since) return false;
  const day = (d: Date) => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Edmonton' }).format(d);
  return day(new Date(since)) === day(new Date());
}

/** `/onboarding` without a business or type: resume an unfinished application, else show the type picker. */
export function OnboardingEntry({ search }: { search: OnboardingSearch }) {
  const businesses = useQuery({ ...businessesQuery, enabled: !search.m && !search.type });
  const applicant = businesses.data?.find(b => b.status === 'applicant');
  const resume = useQuery({ ...onboardingQuery(applicant?.id ?? ''), enabled: !!applicant });
  if (!search.m && !search.type) {
    if (businesses.isPending || (applicant && resume.isPending)) return <OnboardingLayout step="account" type={undefined} onboarding={undefined} onGo={() => {}}><PageSkeleton kpis={0} /></OnboardingLayout>;
    if (applicant && resume.data) {
      const step: Step = resume.data.step === 'done' ? 'listings' : resume.data.step;
      return <Navigate to="/onboarding/$step" params={{ step }} search={{ m: applicant.id, type: resume.data.type }} replace />;
    }
  }
  return <OnboardingScreen step="account" search={search} />;
}

/** The wizard: loads the application (when there is one) and renders the step. */
export function OnboardingScreen({ step, search }: { step: Step; search: OnboardingSearch }) {
  const t = useOnboardingT();
  const navigate = useNavigate();
  const isNew = useIsNewAccount(search.new);
  const m = search.m;
  const query = useQuery({ ...onboardingQuery(m ?? ''), enabled: !!m });
  const o = query.data;
  const type: MerchantType | undefined = o?.type ?? search.type;

  const go = (s: Step, merchantId = m, t2: MerchantType | undefined = type) =>
    void navigate({ to: '/onboarding/$step', params: { step: s }, search: { m: merchantId, type: t2, ...(search.new ? { new: true } : {}) } });

  if (!m && step !== 'account') return <Navigate to="/onboarding/$step" params={{ step: 'account' }} search={{ type: search.type }} replace />;
  const layout = (body: React.ReactNode) => <OnboardingLayout step={step} type={type} onboarding={o} onGo={s => go(s)}>{body}</OnboardingLayout>;
  if (m && query.isPending) return layout(<PageSkeleton kpis={0} rows={6} />);
  if (m && query.isError) return layout(<ErrorState message={t('loadError')} onRetry={() => void query.refetch()} />);
  if (o && STEPS.indexOf(step) > reachable(o)) {
    const target: Step = o.step === 'done' ? 'listings' : o.step;
    return <Navigate to="/onboarding/$step" params={{ step: target }} search={{ m, type: o.type }} replace />;
  }

  switch (step) {
    case 'account':
      return layout(<AccountStep
        key={o?.merchantId ?? 'new'}
        type={type}
        onboarding={o}
        isNew={isNew}
        onTypeChange={t2 => void navigate({ to: '.', search: s => ({ ...s, type: t2 }), replace: true })}
        onDone={(id, t2) => go('business', id, t2)}
      />);
    case 'business': return layout(<BusinessStep key={o!.merchantId} onboarding={o!} onBack={() => go('account')} onDone={() => go('verification')} />);
    case 'verification': return layout(<VerificationStep onboarding={o!} onBack={() => go('business')} onSubmitted={() => go('review')} />);
    case 'review': return layout(<ReviewStep onboarding={o!} onNext={() => go('page')} />);
    case 'page': return layout(<PageStep onboarding={o!} onBack={() => go('review')} onNext={() => go('listings')} />);
    case 'listings': return layout(<ListingsStep onboarding={o!} onBack={() => go('page')} onDone={() => void navigate({ to: studioHome(o!.merchantId, o!.type) })} />);
  }
}
