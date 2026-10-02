import { test } from '@playwright/test';
import { sweep } from './support';

// S-109: the consumer journeys — search, product, service, cart and checkout, booking, account.
test('home', async ({ page }) => sweep(page, 'consumer', 'home', '/', { signedIn: false }));
test('search', async ({ page }) => sweep(page, 'consumer', 'search', '/search?q=sourdough', { signedIn: false }));
test('product', async ({ page }) => sweep(page, 'consumer', 'product', '/products/P1', { signedIn: false }));
test('services · category', async ({ page }) => sweep(page, 'consumer', 'service-category', '/services/mobile-mechanic', { signedIn: false }));
test('provider page', async ({ page }) => sweep(page, 'consumer', 'provider', '/providers/prairie-wrench', { signedIn: false }));
test('booking', async ({ page }) => sweep(page, 'consumer', 'booking', '/providers/prairie-wrench/book', { signedIn: true }));
test('cart & checkout', async ({ page }) => sweep(page, 'consumer', 'cart', '/cart', { signedIn: true }));
test('account', async ({ page }) => sweep(page, 'consumer', 'account', '/account', { signedIn: true }));
test('orders & bookings', async ({ page }) => sweep(page, 'consumer', 'orders', '/account/orders', { signedIn: true }));
test('sign-in', async ({ page }) => sweep(page, 'consumer', 'sign-in', '/sign-in', { signedIn: false }));
