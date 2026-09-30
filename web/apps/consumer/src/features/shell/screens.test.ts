import { describe, expect, it } from 'vitest';
import { SCREENS, screenFor } from './screens';

describe('screenFor', () => {
  it('maps paths to design 06 screens, static segments first', () => {
    expect(screenFor('/')).toBe('home');
    expect(screenFor('/food/checkout')).toBe('foodCheckout');
    expect(screenFor('/food/pho-dau-bo')).toBe('restaurant');
    expect(screenFor('/services/plumber/providers')).toBe('providers');
    expect(screenFor('/services/plumber/')).toBe('svcCategory');
    expect(screenFor('/account/orders')).toBe('orders');
    expect(screenFor('/nowhere/at/all')).toBeUndefined();
  });

  it('covers every consumer state of docs/SCREENS.md', () => {
    const required = ['home', 'location', 'search', 'category', 'svcCategory', 'shop', 'product', 'cart', 'confirmed', 'food', 'restaurant', 'foodCheckout', 'foodTrack', 'services', 'providers', 'provider', 'book', 'quote', 'orders', 'account', 'signIn', 'register'];
    expect(Object.keys(SCREENS)).toEqual(expect.arrayContaining(required));
  });
});
