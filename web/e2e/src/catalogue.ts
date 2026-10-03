import { createHash } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import { expect, type Page } from '@playwright/test';
import { env } from './env';

export interface Product {
  title: string;
  brand: string;
  description: string;
  categoryLevel1: string;
  categoryLevel2: string;
  price: string;
  stock: number;
}

/**
 * A 1000 × 1000 PNG of a "product" on white (the image standard: ≥ 1000 px, pure white background), drawn from `seed`
 * so every listing gets its own picture — vetting flags an image the seller already used.
 */
export function productPhoto(seed: string, size = 1000): Buffer {
  const [r, g, b, offset] = createHash('sha256').update(seed).digest();
  const table = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c >>> 0;
  });
  const crc = (bytes: Buffer) => {
    let c = 0xffffffff;
    for (const b of bytes) c = table[(c ^ b) & 255]! ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (type: string, data: Buffer) => {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type), data]);
    const sum = Buffer.alloc(4);
    sum.writeUInt32BE(crc(body));
    return Buffer.concat([length, body, sum]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0);
  header.writeUInt32BE(size, 4);
  header[8] = 8; // bit depth
  header[9] = 2; // RGB
  const white = Buffer.alloc(1 + size * 3, 255);
  white[0] = 0;
  const product = Buffer.from(white);
  const left = Math.floor(size * 0.2) + (offset! % Math.floor(size * 0.2));
  for (let x = left; x < left + Math.floor(size * 0.4); x++) product.set([r! % 200, g! % 200, b! % 200], 1 + x * 3);
  const rows = Array.from({ length: size }, (_, y) => (y > size * 0.2 && y < size * 0.8 ? product : white));
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(Buffer.concat(rows))),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/**
 * Catalogue publish in the Studio's product editor (design 02 `product_new`): identity & category, a photo, price and
 * stock, compliance → "Submit for vetting" → the listing's status. Returns the listing id and the status it reached
 * (the automated vetting decides: "Approved · live", or "In review · flagged" for a Northline reviewer).
 */
export async function submitProduct(page: Page, merchantId: string, product: Product): Promise<{ listingId: string; status: string }> {
  await page.goto(`${env.urls.studio}/b/${merchantId}/listings/new`);
  await page.getByLabel('Identifier type').selectOption({ label: 'None (handmade / local)' });
  await page.getByLabel('Title').fill(product.title);
  await page.getByLabel('Brand', { exact: true }).fill(product.brand);
  await page.getByLabel('Category level 1').selectOption({ label: product.categoryLevel1 });
  await page.getByLabel('Category level 2').selectOption({ label: product.categoryLevel2 });
  await page.getByLabel('Description').fill(product.description);

  await page.getByRole('tab', { name: /^Images/ }).click();
  await page.getByTestId('image-input').setInputFiles({ name: 'product.png', mimeType: 'image/png', buffer: productPhoto(product.title) });
  await expect(page.getByRole('tab', { name: 'Images · complete' })).toBeVisible();

  await page.getByRole('tab', { name: /^Price, stock/ }).click();
  await page.getByLabel('Your price').fill(product.price);
  await page.getByLabel('Stock on hand').fill(String(product.stock));

  await page.getByRole('tab', { name: /^Compliance/ }).click();
  await page.getByLabel('Country of origin').selectOption({ label: 'Canada' });
  for (const box of [/Not a restricted product/, /Bilingual labelling confirmed/]) {
    await page.locator('label.nl-check', { hasText: box }).locator('.nl-box').click();
  }
  for (const tab of ['Identity & category', 'Images', 'Price, stock & fulfilment', 'Compliance']) {
    await expect(page.getByRole('tab', { name: `${tab} · complete` })).toBeVisible();
  }

  await page.getByRole('button', { name: 'Submit for vetting' }).click();
  await expect(page).toHaveURL(/\/listings\/[0-9A-Z]{26}/);
  const listingId = page.url().match(/\/listings\/([0-9A-Z]{26})/)![1]!;
  // The automated checks run after the submit: reload until the editor shows their outcome.
  const decided = /Approved · live|In review · flagged/;
  await expect(async () => {
    await page.reload();
    await expect(page.getByRole('status').filter({ hasText: decided })).toBeVisible({ timeout: 3_000 });
  }).toPass({ timeout: 60_000 });
  const status = (await page.getByRole('status').filter({ hasText: decided }).textContent())!.trim();
  return { listingId, status };
}

/**
 * Catalogue publish end to end: the owner submits the product; when the automated checks flag it, a Northline reviewer
 * approves it in the console's vetting queue (S-92). Ends with the listing live in the Studio.
 */
export async function publishProduct(owner: Page, staff: Page, merchantId: string, product: Product): Promise<string> {
  const { listingId, status } = await submitProduct(owner, merchantId, product);
  if (status.startsWith('In review')) {
    await staff.goto(`${env.urls.console}/vetting`);
    await staff.getByRole('searchbox', { name: 'Search table' }).fill(product.title);
    await staff.getByRole('button', { name: `Approve · ${product.title}` }).click();
    await expect(staff.getByRole('row').filter({ hasText: product.title })).toContainText('Approved');
    await expect(async () => {
      await owner.reload();
      await expect(owner.getByRole('status').filter({ hasText: 'Approved · live' })).toBeVisible({ timeout: 3_000 });
    }).toPass({ timeout: 60_000 });
  }
  return listingId;
}
