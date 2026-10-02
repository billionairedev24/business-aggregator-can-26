import { test } from '@playwright/test';
import { sweep } from './support';

// S-109: the Studio journeys — sign-in, onboarding, catalogue editing, orders, finance, the kitchen display.
const PROVIDER = '01J9ZD3V00000000000000PWM1', SELLER = '01J9ZD3V00000000000000PWP1', KITCHEN = '01J9ZD3V00000000000000PDB1';

test('sign-in (signed out)', async ({ page }) => sweep(page, 'studio', 'sign-in', '/sign-in', { signedIn: false }));
test('register (signed out)', async ({ page }) => sweep(page, 'studio', 'register', '/register', { signedIn: false }));
test('onboarding · business', async ({ page }) => sweep(page, 'studio', 'onboarding-business', '/onboarding/business?m=01J9ZD3V00000000000000ONB1&type=provider', { signedIn: true }));
test('catalogue · listings', async ({ page }) => sweep(page, 'studio', 'listings', `/b/${SELLER}/listings`, { signedIn: true }));
test('catalogue · listing editor', async ({ page }) => sweep(page, 'studio', 'listing-editor', `/b/${SELLER}/listings/p1`, { signedIn: true }));
test('orders', async ({ page }) => sweep(page, 'studio', 'orders', `/b/${SELLER}/orders`, { signedIn: true }));
test('finance · earnings', async ({ page }) => sweep(page, 'studio', 'earnings', `/b/${PROVIDER}/earnings`, { signedIn: true }));
test('finance · payouts', async ({ page }) => sweep(page, 'studio', 'payouts', `/b/${PROVIDER}/payouts`, { signedIn: true }));
test('finance · reports (charts)', async ({ page }) => sweep(page, 'studio', 'reports', `/b/${PROVIDER}/reports`, { signedIn: true }));
test('kitchen display', async ({ page }) => sweep(page, 'studio', 'kds', `/b/${KITCHEN}/kitchen/live`, { signedIn: true }));
test('kitchen · menu builder', async ({ page }) => sweep(page, 'studio', 'menu', `/b/${KITCHEN}/kitchen/menu`, { signedIn: true }));
test('settings', async ({ page }) => sweep(page, 'studio', 'settings', `/b/${PROVIDER}/settings`, { signedIn: true }));
