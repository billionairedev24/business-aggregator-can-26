import { describe, expect, it } from 'vitest';
import { homeScreen, screenFromPath, screensFor } from './nav';
import { buildNav } from './navMenu';

const t = ((k: string) => k) as never;

describe('portal navigation (design navAll / navKitchen)', () => {
  it('provider: appointments + availability, no orders', () => {
    const s = screensFor('provider');
    expect(s).toContain('appointments'); expect(s).toContain('availability'); expect(s).not.toContain('orders');
  });
  it('seller: orders, no appointments or availability', () => {
    const s = screensFor('seller');
    expect(s).toContain('orders'); expect(s).not.toContain('appointments'); expect(s).not.toContain('availability');
  });
  it('both: everything except kitchen screens', () => {
    const s = screensFor('both');
    expect(s).toEqual(expect.arrayContaining(['appointments', 'orders', 'availability'])); expect(s).not.toContain('kds');
  });
  it('kitchen opens on live orders with no dashboard', () => {
    expect(homeScreen('kitchen')).toBe('kds');
    const nav = buildNav('kitchen', 'm1', t, {});
    expect(nav.pinned).toHaveLength(0);
    expect(nav.groups.map(g => g.label)).toEqual(['g_kitchen', 'g_finance', 'g_account', 'g_help']);
  });
  it('labels follow the portal', () => {
    const label = (type: 'provider' | 'seller' | 'both', key: string) => buildNav(type, 'm', t, {}).groups.flatMap(g => g.items).find(i => i.key === key)?.label;
    expect(label('seller', 'products')).toBe('products_seller');
    expect(label('provider', 'products')).toBe('products_provider');
    expect(label('both', 'products')).toBe('products_both');
    expect(label('seller', 'storefront')).toBe('storefront_seller');
  });
  it('badges attach to items', () => {
    const nav = buildNav('provider', 'm', t, { appointments: '3' });
    expect(nav.groups[0]!.items[0]).toMatchObject({ key: 'appointments', badge: '3', href: '/b/m/appointments' });
  });
  it('sub-pages highlight their parent', () => {
    expect(screenFromPath('/b/m/listings/new')).toBe('products');
    expect(screenFromPath('/b/m/kitchen/menu')).toBe('menu');
    expect(screenFromPath('/b/m')).toBe('dashboard');
    expect(screenFromPath('/b/m/')).toBe('dashboard');
  });
});
