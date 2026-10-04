import { expect, type Locator } from '@playwright/test';

/**
 * The consumer site renders on the server: its buttons exist before React has attached their handlers, and a click
 * that early does nothing. Waits until React owns `locator`'s element (its props are attached) — after a hydration
 * mismatch React replaces the server's elements, and the poll follows the new one.
 */
export async function hydrated(locator: Locator): Promise<Locator> {
  await expect
    .poll(() => locator.evaluate(el => Object.keys(el).some(k => k.startsWith('__reactProps'))), { message: 'React has hydrated the element', timeout: 60_000 })
    .toBe(true);
  return locator;
}
