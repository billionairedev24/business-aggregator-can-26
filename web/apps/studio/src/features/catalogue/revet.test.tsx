import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { I18nProvider } from '@northline/ui';
import { M, renderScreen, stubFetch } from './testing';
import type { ServiceDetail } from './api';

vi.mock('../shell/api', () => ({
  useMerchantId: () => '01J9ZD3V00000000000000PWP1',
  useMerchant: () => ({ id: '01J9ZD3V00000000000000PWP1', displayName: 'Prairie Wrench', type: 'provider', tier: 'master', status: 'active', role: 'owner' }),
  useRole: () => 'owner',
}));
vi.mock('@tanstack/react-router', async orig => ({
  ...(await orig<typeof import('@tanstack/react-router')>()),
  useNavigate: () => vi.fn(),
  Link: ({ children }: { children: React.ReactNode }) => <a href="#">{children}</a>,
}));

import { ServiceEditor } from './ServiceEditor';

const base = `/api/v1/merchants/${M}`;
const service = (over: Partial<ServiceDetail>): ServiceDetail => ({
  id: 's1', kind: 'service', vetting: 'approved', status: 'live', vettingFlags: [], revetReasons: [], submittedAt: '2026-09-30T15:58:00Z',
  updatedAt: '2026-09-30T16:00:00Z', completeness: { percent: 100, done: 4, total: 4, missing: [] },
  name: 'Brake inspection', categoryId: null, pricingMode: 'fixed', priceCents: 8900, durationMin: 60, bufferMin: 20,
  included: 'Pads and rotors', instantBook: true, sku: 'SVC-BI', ...over,
});

afterEach(() => vi.unstubAllGlobals());

describe('S-39 re-vetting in the editor', () => {
  it('warns on an approved listing that price or category changes send it back to vetting', () => {
    stubFetch({ [`GET ${base}/catalogue/categories`]: { items: [] } });
    renderScreen(<ServiceEditor portal="provider" detail={service({})} />);
    expect(screen.getByText(/Changing the price or category sends this listing back to vetting/)).toBeTruthy();
    expect(screen.queryByText('Back in vetting')).toBeNull();
  });

  it('explains why a listing is back in vetting and that customers do not see it', () => {
    stubFetch({ [`GET ${base}/catalogue/categories`]: { items: [] } });
    renderScreen(<ServiceEditor portal="provider" detail={service({ vetting: 'pending', revetReasons: ['price', 'category'] })} />);
    expect(screen.getByText('Back in vetting')).toBeTruthy();
    expect(screen.getByText(/Changed: price and category\. Customers don't see this listing/)).toBeTruthy();
    expect(screen.getByText('Submitted · vetting')).toBeTruthy();
    expect(screen.queryByText(/sends this listing back to vetting/)).toBeNull();
  });

  it('says a reviewer looks at it when the re-vet was flagged', () => {
    stubFetch({ [`GET ${base}/catalogue/categories`]: { items: [] } });
    renderScreen(<ServiceEditor portal="provider" detail={service({ vetting: 'pending', vettingFlags: ['missing_licence'], revetReasons: ['category'] })} />);
    expect(screen.getByText(/Changed: category\. The checks flagged it/)).toBeTruthy();
  });

  it('a first submission shows no re-vetting notice', () => {
    stubFetch({ [`GET ${base}/catalogue/categories`]: { items: [] } });
    renderScreen(<ServiceEditor portal="provider" detail={service({ vetting: 'pending' })} />);
    expect(screen.queryByText('Back in vetting')).toBeNull();
  });

  it('fr-CA', () => {
    stubFetch({ [`GET ${base}/catalogue/categories`]: { items: [] } });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={qc}><I18nProvider initial="fr">
      <ServiceEditor portal="provider" detail={service({ vetting: 'pending', revetReasons: ['price'] })} />
    </I18nProvider></QueryClientProvider>);
    expect(screen.getByText('De nouveau en contrôle')).toBeTruthy();
    expect(screen.getByText(/Modifié : prix\. Les clients ne voient pas cette annonce/)).toBeTruthy();
  });
});
