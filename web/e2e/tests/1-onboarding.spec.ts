import { publishProduct } from '../src/catalogue';
import { env, isLocal } from '../src/env';
import { expect, test, unique } from '../src/fixtures';
import { email } from '../src/outbox';

/**
 * Journey 2 — onboarding: the owner applies for a new business in the Studio (account, business details, every
 * verification check), a Northline reviewer approves it in the console's verification queue, the owner is told, and the
 * business publishes its first product, which customers can then open.
 */
test('a new business is onboarded, approved by a console reviewer and publishes its catalogue', async ({ owner, staff, consumer }) => {
  test.skip(!isLocal, 'identity verification goes through Stripe Identity outside local (docs/runbooks/e2e.md § What target mode skips)');
  const data = env.data.onboarding;
  const name = unique('E2E Hardware');
  const ownerName = env.personas.owner.firstName ?? 'the owner';
  let merchantId = '';

  await test.step('owner: account step', async () => {
    await owner.goto(`${env.urls.studio}/onboarding?type=${data.type}`);
    await expect(owner.getByRole('heading', { level: 1 })).toContainText(`Set up your ${data.type} account`);
    await owner.getByRole('button', { name: new RegExp(`Continue as ${ownerName}`) }).click();
    await expect(owner).toHaveURL(/\/onboarding\/business\?m=[0-9A-Z]{26}/);
    merchantId = new URL(owner.url()).searchParams.get('m')!;
  });

  await test.step('owner: business details', async () => {
    await owner.getByLabel('Store name (shown to customers)').fill(name);
    await owner.getByLabel('Legal entity').fill(`${name} (sole proprietor)`);
    await owner.getByLabel('Legal name of owner · required').fill(`${ownerName} E2E`);
    await owner.getByLabel('Home / business address · required').fill(data.address);
    await owner.getByRole('combobox', { name: 'Departments you sell in' }).click();
    await owner.getByRole('option', { name: data.department }).click();
    await owner.keyboard.press('Escape');
    await owner.getByLabel('Pickup / warehouse address for couriers').fill(data.address);
    await owner.getByLabel('Short description (shown to customers)').fill('Hardware for the end-to-end suite.');
    await owner.getByRole('button', { name: 'Continue to verification' }).click();
    await expect(owner.getByRole('heading', { name: 'Verification', level: 1 })).toBeVisible();
  });

  await test.step('owner: identity with (fake) Stripe Identity', async () => {
    await owner.getByRole('button', { name: 'Start with Stripe' }).click();
    await owner.getByRole('dialog').getByRole('button', { name: /verify now|Try again as me/ }).click();
    await expect(owner.getByRole('heading', { name: 'Fake Stripe Identity' })).toBeVisible();
    await owner.getByRole('button', { name: 'verified', exact: true }).click();
    await expect(owner).toHaveURL(/identity=returned/);
    await expect(owner.getByRole('listitem').filter({ hasText: 'Identity (Stripe KYC)' })).toContainText('Passed');
  });

  await test.step('owner: the other checks', async () => {
    const check = (title: string) => owner.getByRole('listitem').filter({ hasText: title });
    await check('Business registration').getByRole('button', { name: 'Look up' }).click();
    await expect(check('Business registration').getByRole('button')).toHaveCount(0);

    await check('GST/HST registration').getByRole('button', { name: 'Enter BN' }).click();
    await owner.getByLabel('Business number with GST/HST account').fill(data.gstNumber);
    await owner.getByRole('dialog').getByRole('button', { name: 'Verify' }).click();
    await expect(check('GST/HST registration')).toContainText('active');

    await check('Product-category permits').getByRole('button', { name: 'Declare categories' }).click();
    await owner.getByRole('dialog').getByRole('button', { name: 'Submit' }).click();
    await expect(check('Product-category permits')).toContainText('None required');

    await check('safety & labelling attestation').getByRole('button', { name: 'Read & sign' }).click();
    await owner.getByRole('dialog').locator('label.nl-check .nl-box').click();
    await owner.getByRole('dialog').getByRole('button', { name: 'Sign' }).click();
    await expect(check('safety & labelling attestation').getByRole('button')).toHaveCount(0);

    await check('Returns policy').getByRole('button', { name: 'Choose' }).click();
    await owner.getByRole('dialog').getByRole('radio', { name: /Northline standard/ }).click();
    await owner.getByRole('dialog').getByRole('button', { name: 'Submit' }).click();
    await expect(check('Returns policy').getByRole('button')).toHaveCount(0);

    for (const [title, button] of [['Bank account for payouts', 'Connect bank'], ['Second factor', 'Set up passkey']] as const) {
      const item = check(title);
      if (await item.getByRole('button', { name: button }).isVisible()) await item.getByRole('button', { name: button }).click();
      await expect(item.getByRole('button')).toHaveCount(0);
    }
  });

  await test.step('owner: submit for review', async () => {
    await owner.getByRole('button', { name: 'Submit for review' }).click();
    await expect(owner.getByRole('heading', { name: "We're checking your application.", level: 1 })).toBeVisible();
    await expect(owner.getByRole('listitem').filter({ hasText: 'Trust & safety review' })).toContainText('in queue');
  });

  const decided = new Date();
  await test.step('console reviewer: approve in the verification queue', async () => {
    await staff.goto(`${env.urls.console}/verification`);
    await staff.getByRole('searchbox', { name: 'Search table' }).fill(name);
    const row = staff.getByRole('row').filter({ hasText: name });
    await expect(row).toContainText('KYC ✓');
    await row.getByRole('button', { name: `Approve · ${name}` }).click();
    await expect(staff.getByRole('status').filter({ hasText: `${name} approved.` })).toBeVisible();
  });

  await test.step('owner: told by email, the business is live in the Studio', async () => {
    const message = await email(env.personas.owner.identifier, 'application-decision', decided);
    expect(message.subject).toBe(`${name} is approved on Northline`);
    await owner.goto(`${env.urls.studio}/b/${merchantId}`);
    await expect(owner.getByRole('banner')).toContainText(name);
    await expect(owner.getByRole('banner')).toContainText('Registered tier');
  });

  const title = unique('E2E Claw Hammer');
  let listingId = '';
  await test.step('owner: publishes the first product', async () => {
    listingId = await publishProduct(owner, staff, merchantId, {
      title, brand: 'Northline E2E', description: 'A forged steel claw hammer for the end-to-end suite.',
      categoryLevel1: data.categoryLevel1, categoryLevel2: data.categoryLevel2, price: '24.99', stock: 5,
    });
  });

  await test.step('owner: the product is live in the catalogue', async () => {
    await owner.goto(`${env.urls.studio}/b/${merchantId}/listings`);
    const row = owner.getByRole('row').filter({ hasText: title });
    await expect(row).toContainText('Approved');
    await expect(row.getByRole('button', { name: `Hide · ${title}` })).toBeVisible(); // it is live: it can be hidden
  });

  await test.step('customer: the product page is public', async () => {
    await consumer.goto(`${env.urls.consumer}/products/${listingId}`);
    await expect(consumer.getByRole('heading', { level: 1 })).toHaveText(title);
  });
});
