import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderConsole, staffApi, type Call } from '../../test/render';
import type { Category, Screen } from './api';
import { expectNoAxeViolations } from '@northline/a11y/vitest';

const cat = (o: Partial<Category>): Category => ({
  id: 'service.automotive.mobile-mechanic', parentId: 'service.automotive', root: 'service', group: false, nameEn: 'Mobile mechanic', nameFr: 'Mécanicien mobile',
  bookingType: 'visit', regulatedRegistry: null, requiresVsCheck: false, regulators: [], sellers: 44, liveIn: ['AB', 'BC'], liveListings: 60,
  medianPriceCents: 8_900, priceMode: 'fixed', ...o,
});
const SCREEN: Screen = {
  asOf: '2026-09-08T18:00:00Z', serviceCategories: 62, shopDepartments: 9,
  categories: [
    cat({ id: 'service.automotive', parentId: null, group: true, nameEn: 'Automotive', nameFr: 'Automobile', sellers: 0, liveIn: [] }),
    cat({ id: 'shop.food-and-grocery', parentId: null, root: 'shop', group: true, nameEn: 'Food & grocery', nameFr: 'Alimentation', sellers: 0, liveIn: [] }),
    cat({ regulators: [{ province: 'AB', regulator: 'amvic' }, { province: 'BC', regulator: null }] }),
    cat({ id: 'service.home.electrician', parentId: 'service.automotive', nameEn: 'Electrician', nameFr: 'Électricien', regulatedRegistry: 'Safety Codes', sellers: 31,
      liveIn: ['AB'], medianPriceCents: 14_000, priceMode: 'hourly' }),
    cat({ id: 'service.automotive.real-estate', nameEn: 'Real-estate agent', sellers: 17, liveIn: ['AB'], medianPriceCents: null, priceMode: 'quote' }),
    cat({ id: 'shop.food-and-grocery.butcher', parentId: 'shop.food-and-grocery', root: 'shop', nameEn: 'Butcher', nameFr: 'Boucherie', bookingType: null, sellers: 4, liveIn: ['AB'],
      medianPriceCents: null, priceMode: null }),
  ],
  regulators: [{ code: 'amvic', name: 'AMVIC', province: 'AB', website: 'https://www.amvic.org', categories: 1 }],
  limits: [
    { merchantType: 'provider', max: 10, businessesAbove: 0, updatedAt: '2026-09-01T00:00:00Z', updatedBy: 'system' },
    { merchantType: 'seller', max: 5, businessesAbove: 2, updatedAt: '2026-09-01T00:00:00Z', updatedBy: 'system' },
    { merchantType: 'both', max: 10, businessesAbove: 0, updatedAt: '2026-09-01T00:00:00Z', updatedBy: 'system' },
    { merchantType: 'kitchen', max: 3, businessesAbove: 0, updatedAt: '2026-09-01T00:00:00Z', updatedBy: 'system' },
  ],
  suggestions: [{ id: 'suggested:paintless-dent-repair', name: 'Paintless dent repair', businesses: [
    { id: 'm1', name: 'Bow Dent Co.', type: 'provider', province: 'AB', status: 'active' }, { id: 'm2', name: 'Prairie Dents', type: 'provider', province: 'AB', status: 'pending' },
  ] }],
};

function api(roles: Parameters<typeof staffApi>[0], extra?: (c: Call) => { status?: number; body?: unknown } | undefined) {
  return staffApi(roles, c => {
    const hit = extra?.(c);
    if (hit) return hit;
    if (c.method === 'GET' && c.url.endsWith('/api/v1/console/taxonomy')) return { body: SCREEN };
    if (c.url.includes('/taxonomy/categories/') && c.url.includes('/regulators/')) return { body: SCREEN.categories[2] };
    if (c.url.includes('/taxonomy/categories')) return { status: c.method === 'POST' ? 201 : 200, body: SCREEN.categories[2] };
    if (c.url.includes('/taxonomy/regulators')) return { status: c.method === 'POST' ? 201 : 200, body: SCREEN.regulators[0] };
    if (c.url.includes('/taxonomy/limits/')) return { body: { ...SCREEN.limits[3], max: 4 } };
    if (c.url.includes('/taxonomy/suggestions/')) return { body: { category: SCREEN.categories[2], moved: 1, alreadyHeld: 1 } };
    return undefined;
  });
}
const card = (text: string) => screen.getAllByText(text).map(e => e.closest('tr, .nl-dt-card') as HTMLElement | null).find(Boolean) as HTMLElement;

describe('catalogue taxonomy (S-94, design 03)', () => {
  it('lists the categories with root, regulators by province, live provinces, sellers and median price', async () => {
    api(['admin']);
    renderConsole('/catalogue');
    expect(await screen.findByRole('heading', { level: 1, name: '62 service categories · 9 shop departments · per-province rules' })).toBeTruthy();
    await expectNoAxeViolations(document.body); // S-109
    const mechanic = card('Mobile mechanic');
    expect(within(mechanic).getByText('Services › Automotive')).toBeTruthy();
    expect(within(mechanic).getByText('Yes · AMVIC (AB), BC: none')).toBeTruthy();
    expect(within(mechanic).getByText('$89')).toBeTruthy();
    expect(within(card('Electrician')).getByText('Yes · Safety Codes')).toBeTruthy();
    expect(within(card('Electrician')).getByText('$140/h')).toBeTruthy();
    expect(within(card('Real-estate agent')).getByText('quote')).toBeTruthy();
    expect(within(card('Butcher')).getByText('Shop › Food & grocery')).toBeTruthy();
    expect(within(card('Butcher')).getByText('No')).toBeTruthy();
    expect(screen.getByText('2 businesses hold more than this; they keep them until they change their categories.')).toBeTruthy();
    expect(screen.getByText('2 businesses · Bow Dent Co., Prairie Dents')).toBeTruthy();
  });

  it('adds a category under a group, and shows the api’s messages', async () => {
    let first = true;
    const calls = api(['admin'], c => {
      if (c.method === 'POST' && c.url.endsWith('/taxonomy/categories') && first) {
        first = false;
        return { status: 422, body: { errors: [{ field: 'nameEn', rule: 'length', message: 'Enter the English name, 1 to 80 characters.' }] } };
      }
      return undefined;
    });
    const user = userEvent.setup({ delay: null });
    renderConsole('/catalogue');
    await user.click(await screen.findByRole('button', { name: 'Add category' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add category' });
    await user.click(within(dialog).getByRole('button', { name: 'Add' }));
    expect(await within(dialog).findByText('Enter the English name, 1 to 80 characters.')).toBeTruthy();
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Group' }), 'service.automotive');
    await user.type(within(dialog).getByRole('textbox', { name: 'English name' }), 'Rust proofing');
    await user.type(within(dialog).getByRole('textbox', { name: 'French name' }), 'Antirouille');
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Booking type' }), 'visit');
    await user.type(within(dialog).getByRole('textbox', { name: /Licence registry/ }), 'AMVIC');
    await user.click(within(dialog).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(calls.filter(c => c.method === 'POST' && c.url.endsWith('/taxonomy/categories')).at(-1)?.body).toEqual({
      root: 'service', parentId: 'service.automotive', nameEn: 'Rust proofing', nameFr: 'Antirouille', bookingType: 'visit', regulatedRegistry: 'AMVIC', requiresVsCheck: false,
    }));
  });

  it('edits a category and sets its regulator in a province', async () => {
    const calls = api(['admin']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/catalogue');
    await user.click(await screen.findByText('Mobile mechanic'));
    const dialog = await screen.findByRole('dialog', { name: 'Edit · Mobile mechanic' });
    const alberta = within(dialog).getByRole('combobox', { name: 'Alberta' }) as HTMLSelectElement;
    expect(alberta.value).toBe('amvic');
    await user.selectOptions(alberta, 'none');
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/categories/service.automotive.mobile-mechanic/regulators/AB'))?.body).toEqual({ regulator: 'none' }));
    await user.click(within(dialog).getByRole('checkbox', { name: 'Needs a vulnerable-sector check' }));
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(calls.find(c => c.method === 'PUT' && c.url.endsWith('/categories/service.automotive.mobile-mechanic'))?.body)
      .toMatchObject({ nameEn: 'Mobile mechanic', nameFr: 'Mécanicien mobile', bookingType: 'visit', requiresVsCheck: true }));
  });

  it('sets the age restriction of a shop category (2026-10-04)', async () => {
    const calls = api(['admin']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/catalogue');
    await user.click(await screen.findByText('Butcher'));
    const dialog = await screen.findByRole('dialog', { name: 'Edit · Butcher' });
    const age = within(dialog).getByRole('combobox', { name: /Age restriction/ }) as HTMLSelectElement;
    expect(age.value).toBe('');
    await user.selectOptions(age, 'alcohol');
    await waitFor(() => expect(calls.find(c => c.method === 'PUT' && c.url.endsWith('/categories/shop.food-and-grocery.butcher/age-class'))?.body).toEqual({ ageClass: 'alcohol' }));
  });

  it('offers no age restriction for services', async () => {
    api(['admin']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/catalogue');
    await user.click(await screen.findByText('Mobile mechanic'));
    const dialog = await screen.findByRole('dialog', { name: 'Edit · Mobile mechanic' });
    expect(within(dialog).queryByRole('combobox', { name: /Age restriction/ })).toBeNull();
  });

  it('adds a regulator, and changes a category limit', async () => {
    const calls = api(['admin']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/catalogue');
    await user.click(await screen.findByRole('button', { name: 'Add regulator' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add regulator' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Code' }), 'tsbc');
    await user.type(within(dialog).getByRole('textbox', { name: 'Name' }), 'Technical Safety BC');
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Province' }), 'BC');
    await user.click(within(dialog).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(calls.find(c => c.method === 'POST' && c.url.endsWith('/taxonomy/regulators'))?.body)
      .toEqual({ code: 'tsbc', name: 'Technical Safety BC', province: 'BC' }));
    const kitchen = screen.getByRole('spinbutton', { name: 'Most categories · Kitchen' });
    await user.clear(kitchen);
    await user.type(kitchen, '4');
    await user.click(within(kitchen.closest('form') as HTMLElement).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/taxonomy/limits/kitchen'))?.body).toEqual({ max: 4 }));
  });

  it('merges a suggestion into a category, or approves it as a new one', async () => {
    const calls = api(['admin']);
    const user = userEvent.setup({ delay: null });
    renderConsole('/catalogue');
    await user.click(await screen.findByRole('button', { name: 'Merge into…' }));
    let dialog = await screen.findByRole('dialog', { name: 'Merge “Paintless dent repair” into' });
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Category' }), 'service.automotive.mobile-mechanic');
    await user.click(within(dialog).getByRole('button', { name: 'Merge' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/suggestions/suggested%3Apaintless-dent-repair/merge'))?.body)
      .toEqual({ categoryId: 'service.automotive.mobile-mechanic' }));
    expect(await screen.findByText('2 businesses moved to Automotive › Mobile mechanic.')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Approve as new category' }));
    dialog = await screen.findByRole('dialog', { name: 'Approve as a new category · Paintless dent repair' });
    expect((within(dialog).getByRole('textbox', { name: 'English name' }) as HTMLInputElement).value).toBe('Paintless dent repair');
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Group' }), 'service.automotive');
    await user.click(within(dialog).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/suggestions/suggested%3Apaintless-dent-repair/approve'))?.body)
      .toMatchObject({ parentId: 'service.automotive', nameEn: 'Paintless dent repair' }));
  });

  it('is admin only, and speaks French', async () => {
    staffApi(['trust_safety'], c => (c.url.includes('/api/v1/console/overview') ? { status: 500 } : undefined));
    const first = renderConsole('/catalogue');
    expect(await screen.findByText(/Not available in this role\./)).toBeTruthy();
    first.unmount();
    api(['admin']);
    renderConsole('/catalogue', { locale: 'fr' });
    expect(await screen.findByRole('heading', { level: 1, name: '62 catégories de services · 9 rayons de boutique · règles par province' })).toBeTruthy();
    expect(within(card('Mécanicien mobile')).getByText('Oui · AMVIC (AB), BC : aucun')).toBeTruthy();
  });
});
