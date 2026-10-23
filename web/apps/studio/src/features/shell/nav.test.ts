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
  it('kitchen opens on live orders; its dashboard is pinned first (E-15)', () => {
    expect(homeScreen('kitchen')).toBe('kds');
    const nav = buildNav('kitchen', 'm1', t, {});
    expect(nav.pinned.map(i => i.key)).toEqual(['dashboard']);
    expect(nav.groups.map(g => g.label)).toEqual(['g_operations', 'g_catalogue', 'g_money', 'g_store', 'g_account']);
    expect(nav.groups[0]!.items.map(i => i.key)).toEqual(['kds', 'messages']);
    expect(nav.groups[1]!.items.map(i => i.key)).toEqual(['menu', 'combos', 'kitchenHours']);
  });
  it('every screen of a portal is in its menu exactly once', () => {
    for (const type of ['provider', 'seller', 'kitchen', 'both'] as const) {
      const nav = buildNav(type, 'm', t, {});
      const keys = [...nav.pinned, ...nav.groups.flatMap(g => g.items)].map(i => i.key);
      expect([...keys].sort()).toEqual([...screensFor(type)].sort());
    }
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
    expect(nav.groups.map(g => g.label)).toEqual(['g_operations', 'g_catalogue', 'g_money', 'g_store', 'g_account']);
  });
  it('sub-pages highlight their parent', () => {
    expect(screenFromPath('/b/m/listings/new')).toBe('products');
    expect(screenFromPath('/b/m/kitchen/menu')).toBe('menu');
    expect(screenFromPath('/b/m')).toBe('dashboard');
    expect(screenFromPath('/b/m/')).toBe('dashboard');
  });
});
