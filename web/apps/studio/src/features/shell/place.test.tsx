import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { timeZone } from '@northline/ui';
import { mockFetch, renderWithProviders } from '../../test/render';
import { useAvailabilityT } from '../availability/messages';
import { useComplianceT } from '../compliance/messages';
import { useOnboardingT } from '../onboarding/messages';
import { PlaceValues, ProvincePlace } from './place';

/** S-134: copy names the business's province as a parameter, in English and French — never Alberta in code. */
function Copy() {
  const a = useAvailabilityT();
  const c = useComplianceT();
  const o = useOnboardingT();
  return (
    <ul>
      <li>{a('holidaysTitle')}</li>
      <li>{c('name_privacy')}</li>
      <li>{o('ckd_licence_AMVIC')}</li>
      <li>{o('bs_corp_ex_why')}</li>
    </ul>
  );
}

describe('place parameters', () => {
  it('fills a British Columbia business’s province and privacy law, and its zone', () => {
    mockFetch(() => undefined);
    renderWithProviders(
      <PlaceValues province="British Columbia" provinceIn="in British Columbia" provinceOf="British Columbia" privacyLaw="bc_pipa" timeZone="America/Vancouver">
        <Copy />
      </PlaceValues>,
    );
    expect(screen.getByText('Statutory holidays · British Columbia')).toBeTruthy();
    expect(screen.getByText('Privacy acknowledgement (PIPEDA / British Columbia PIPA)')).toBeTruthy();
    expect(screen.getByText('Required for automotive services in British Columbia.')).toBeTruthy();
    expect(timeZone()).toBe('America/Vancouver');
  });

  it('reads in French with the province’s preposition from the region model', async () => {
    mockFetch(() => undefined);
    renderWithProviders(<ProvincePlace code="QC"><Copy /></ProvincePlace>, { locale: 'fr' });
    expect(await screen.findByText('Jours fériés · Québec')).toBeTruthy();
    expect(screen.getByText('Engagement de confidentialité (LPRPDE / Loi 25 (Québec))')).toBeTruthy();
    expect(screen.getByText('Requis pour les services automobiles au Québec.')).toBeTruthy();
    expect(screen.getByText(/exerçant au Québec\. Il nous faut/)).toBeTruthy();
  });
});
