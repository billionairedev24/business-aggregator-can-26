import AsyncStorage from '@react-native-async-storage/async-storage';
import { act, fireEvent, screen, waitFor } from 'expo-router/testing-library';
import { Share } from 'react-native';

import { setPlusPayment } from '../src/account/plusPayment';
import { setServices } from '../src/services';
import { start, type StartOptions } from './support';

/** S-152 in the app: points activity, Invite a neighbour, Northline Plus and rewards near you (en + fr-CA). */
jest.mock('@stripe/stripe-react-native', () => ({}));

afterEach(async () => {
  setServices(null);
  setPlusPayment(null);
  jest.restoreAllMocks();
  await AsyncStorage.clear();
});

const french = () => (jest.requireMock('expo-localization') as { getLocales: jest.Mock }).getLocales.mockReturnValueOnce([{ languageTag: 'fr-CA' }]);
type Wrap = (f: typeof fetch) => typeof fetch;
interface Sent { method: string; url: string; body: Record<string, unknown>; headers: Record<string, string> }
function recorder(): { sent: Sent[]; wrap: Wrap } {
  const sent: Sent[] = [];
  return {
    sent,
    wrap: (f) =>
      (async (input: RequestInfo | URL, init: RequestInit = {}) => {
        const headers = Object.fromEntries(Object.entries((init.headers ?? {}) as Record<string, string>).map(([k, v]) => [k.toLowerCase(), v]));
        sent.push({ method: (init.method ?? 'GET').toUpperCase(), url: String(input), body: typeof init.body === 'string' && init.body.startsWith('{') ? JSON.parse(init.body) : {}, headers });
        return f(input, init);
      }) as typeof fetch,
  };
}
const signedIn = (url: string, more: Partial<StartOptions> = {}) => start({ signedIn: true, url, ...more });

describe('Points activity (/account/points, the points notification’s link)', () => {
  it('shows the balance, the next expiry, how points work and the activity', async () => {
    await signedIn('/account/points');
    expect(await screen.findByText('How points work')).toBeTruthy();
    expect(screen.getByTestId('points-total')).toHaveTextContent('1,240');
    expect(screen.getByText(/^1420 points expire on /)).toBeTruthy();
    expect(screen.getByText('Completed orders and bookings earn 2 points for every dollar you pay the business, once its money is released.')).toBeTruthy();
    expect(screen.getByText('100 points = $1 off at checkout, before tax.')).toBeTruthy();
    expect(screen.getByText('Points expire 18 months after you earn them.')).toBeTruthy();
    expect(screen.getByText('Spent at checkout')).toBeTruthy();
    expect(screen.getByText('−500')).toBeTruthy();
    expect(screen.getByText('Business bonus')).toBeTruthy();
    expect(screen.getByText('+1,420')).toBeTruthy();
  });

  it('empty, and in French', async () => {
    french();
    const { server, services } = await signedIn('/account/points');
    expect(await screen.findByText('Comment fonctionnent les points')).toBeTruthy();
    expect(screen.getByText('Utilisés au paiement')).toBeTruthy();
    server.account.pointsActivity = [];
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText('Vos points apparaîtront ici après votre première commande ou réservation terminée.')).toBeTruthy();
  });

  it('asks a guest to sign in', async () => {
    await start({ welcomed: true, url: '/account/points' });
    expect(await screen.findByText('Sign in to see your points.')).toBeTruthy();
  });
});

describe('Invite a neighbour', () => {
  it('shows the code and shares the link', async () => {
    const share = jest.spyOn(Share, 'share').mockResolvedValue({ action: 'sharedAction' } as never);
    await signedIn('/account/invite');
    expect(await screen.findByText('ADAK7Q2M')).toBeTruthy();
    expect(screen.getByText('Share your code. When your neighbour’s first order of $20.00 or more is completed, you both get 1000 points ($10.00).')).toBeTruthy();
    expect(screen.getByText('1 neighbour waiting · none rewarded yet · 0 points earned')).toBeTruthy();
    fireEvent.press(screen.getByTestId('invite-share'));
    expect(share).toHaveBeenCalledWith(expect.objectContaining({ message: 'Join me on Northline — use my invite code ADAK7Q2M: https://example.invalid/?invite=ADAK7Q2M' }));
  });

  it('uses a neighbour’s code: the api’s message under the field, then the claim', async () => {
    const rec = recorder();
    const { server } = await signedIn('/account/invite', { wrap: rec.wrap });
    fireEvent.changeText(await screen.findByTestId('invite-claim-code'), 'adak7q2m');
    fireEvent.press(screen.getByTestId('invite-claim'));
    expect(await screen.findByText('That’s your own invite code: share it with a neighbour instead.')).toBeTruthy();
    fireEvent.changeText(screen.getByTestId('invite-claim-code'), 'neighbr2');
    fireEvent.press(screen.getByTestId('invite-claim'));
    expect(await screen.findByText('You used code NEIGHBR2. Your points arrive after your first completed order of $20.00 or more.')).toBeTruthy();
    expect(screen.queryByTestId('invite-claim')).toBeNull();
    expect(rec.sent.filter((s) => s.url.endsWith('/me/referral/claim')).map((s) => s.body)).toEqual([{ code: 'ADAK7Q2M' }, { code: 'NEIGHBR2' }]);
    expect(server.account.referral.claimed?.state).toBe('pending');
  });

  it('is in French', async () => {
    french();
    await signedIn('/account/invite?code=neighbr2');
    expect(await screen.findByText('Votre code d’invitation')).toBeTruthy();
    expect(screen.getByTestId('invite-claim-code').props.value).toBe('NEIGHBR2');
    expect(screen.getByRole('button', { name: 'Utiliser le code' })).toBeTruthy();
  });
});

describe('Northline Plus (wallet)', () => {
  it('starts the free trial, switches plan, cancels at the period end and keeps it', async () => {
    const rec = recorder();
    const { server } = await signedIn('/wallet', { wrap: rec.wrap });
    expect(await screen.findByText('$0 service fee on food orders')).toBeTruthy();
    expect(screen.getByText('$69.00/year')).toBeTruthy();
    fireEvent.press(screen.getByTestId('plan-annual'));
    fireEvent.press(screen.getByTestId('plus-start'));
    expect(await screen.findByText(/^Free trial · ends /)).toBeTruthy();
    const post = rec.sent.find((s) => s.method === 'POST' && s.url.endsWith('/me/plus'))!;
    expect(post.body).toEqual({ plan: 'annual' });
    expect(post.headers['idempotency-key']).toBeTruthy();
    expect(server.account.plus?.plan).toBe('annual');

    fireEvent.press(screen.getByTestId('plus-switch'));
    // S-162: the plan changes at the next renewal
    await waitFor(() => expect(server.account.plus?.nextPlan).toBe('monthly'));
    expect(server.account.plus?.plan).toBe('annual');
    expect(await screen.findByText(/^Switches to Monthly on /)).toBeTruthy();
    expect(screen.getByTestId('plus-keep-plan')).toBeTruthy();
    const patch = rec.sent.find((s) => s.method === 'PATCH')!;
    expect(patch.headers['idempotency-key']).not.toBe(post.headers['idempotency-key']);

    fireEvent.press(screen.getByTestId('plus-cancel'));
    expect(screen.getByText(/^Your household keeps Plus until .*\. Nothing more is charged\.$/)).toBeTruthy();
    fireEvent.press(screen.getByTestId('plus-cancel-yes'));
    expect(await screen.findByText(/^Cancelled · ends /)).toBeTruthy();
    expect(server.account.plus?.cancelAtPeriodEnd).toBe(true);
    fireEvent.press(screen.getByTestId('plus-resume'));
    await waitFor(() => expect(server.account.plus?.cancelAtPeriodEnd).toBe(false));
  });

  it('asks for a card first under Stripe Billing (409 card_required)', async () => {
    const { server, view } = await signedIn('/wallet');
    server.account.plusNeedsCard = true;
    fireEvent.press(await screen.findByTestId('plus-start'));
    expect(await screen.findByText('Plus renews on your default card. Add one in Payment methods, then come back.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('plus-needs-card').findByProps({ accessibilityLabel: 'Payment methods' }));
    await waitFor(() => expect(view.getPathname()).toBe('/account/payments'));
  });

  it('confirms the first payment with the bank when Stripe asks (3-D Secure), and says when the bank refuses', async () => {
    const confirm = jest.fn().mockResolvedValue({ ok: false });
    setPlusPayment({ confirm });
    const answer = (f: typeof fetch) =>
      (async (input: RequestInfo | URL, init: RequestInit = {}) => {
        const r = await f(input, init);
        if ((init.method ?? 'GET') === 'POST' && String(input).endsWith('/me/plus')) {
          const body = await r.json();
          return new Response(JSON.stringify({ ...body, provider: 'stripe', status: 'incomplete', clientSecret: 'pi_fake_secret_fake', publishableKey: 'pk_test_fake' }), { status: 200, headers: { 'content-type': 'application/json' } });
        }
        return r;
      }) as typeof fetch;
    await signedIn('/wallet', { wrap: answer });
    fireEvent.press(await screen.findByTestId('plus-start'));
    await waitFor(() => expect(confirm).toHaveBeenCalledWith('pi_fake_secret_fake', 'pk_test_fake'));
    expect(await screen.findByText('Your bank didn’t confirm the payment. Try again, or use another card in Payment methods.')).toBeTruthy();
  });

  it('S-162: a renewal the bank wants confirmed — the SDK, then the api reads it again', async () => {
    const confirm = jest.fn().mockResolvedValue({ ok: true });
    setPlusPayment({ confirm });
    const rec = recorder();
    const { server, services } = await signedIn('/wallet', { wrap: rec.wrap });
    await screen.findByTestId('plus-start');
    server.account.plus = {
      plan: 'monthly', since: new Date().toISOString(), status: 'past_due',
      payment: { reason: 'authentication_required', totalCents: 839, retryAt: null, clientSecret: 'pi_r_secret_x' },
    };
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText('Your bank wants you to confirm the payment of $8.39.')).toBeTruthy();
    fireEvent.press(screen.getByTestId('plus-confirm'));
    await waitFor(() => expect(confirm).toHaveBeenCalledWith('pi_r_secret_x', 'pk_test_fake'));
    await waitFor(() => expect(rec.sent.some((x) => x.method === 'POST' && x.url.endsWith('/me/plus/confirm'))).toBe(true));
    expect(await screen.findByText(/^Active · renews /)).toBeTruthy();
  });

  it('S-162: a declined renewal says when it’s tried again, and the next payment otherwise', async () => {
    const { server, services } = await signedIn('/wallet');
    await screen.findByTestId('plus-start');
    server.account.plus = {
      plan: 'monthly', since: new Date().toISOString(), status: 'past_due',
      payment: { reason: 'card_declined', totalCents: 839, retryAt: '2026-11-20T12:00:00Z' },
    };
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText(/^Your card was declined for \$8\.39\. We’ll try again on /)).toBeTruthy();
    expect(screen.queryByTestId('plus-confirm')).toBeNull();
    server.account.plus = { plan: 'monthly', since: new Date().toISOString(), status: 'active' };
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText(/^Next payment \$7\.99 on .*, plus tax\.$/)).toBeTruthy();
  });

  it('someone else in the household sees it but doesn’t manage it', async () => {
    const { server, services } = await signedIn('/wallet');
    await screen.findByTestId('plus-start');
    server.account.plus = { plan: 'monthly', since: new Date().toISOString(), status: 'active', payer: false };
    await act(async () => {
      await services.queryClient.invalidateQueries();
    });
    expect(await screen.findByText('The person who started Plus for your household manages it.')).toBeTruthy();
    expect(screen.queryByTestId('plus-cancel')).toBeNull();
    expect(screen.queryByTestId('plus-switch')).toBeNull();
  });

  it('is in French', async () => {
    french();
    await signedIn('/wallet');
    expect(await screen.findByText('Aucuns frais de service sur les commandes de repas')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Commencer l’essai gratuit de 30 jours' })).toBeTruthy();
  });
});

describe('Rewards near you (Home)', () => {
  it('shows the businesses paying extra points, and opens the provider', async () => {
    const { view } = await start({ welcomed: true, url: '/home' });
    expect(await screen.findByText('Rewards near you')).toBeTruthy();
    const card = screen.getByTestId('reward-mer-garage');
    expect(card.props.accessibilityLabel).toMatch(/^Sample Garage, 2× points on Brake service, until /);
    fireEvent.press(card);
    await waitFor(() => expect(view.getPathname()).toBe('/providers/prairie-wrench'));
  });

  it('shows nothing when no reward runs', async () => {
    const none: Wrap = (f) =>
      (async (input: RequestInfo | URL, init: RequestInit = {}) =>
        String(input).includes('/public/rewards') ? new Response(JSON.stringify({ items: [] }), { status: 200, headers: { 'content-type': 'application/json' } }) : f(input, init)) as typeof fetch;
    await start({ welcomed: true, url: '/home', wrap: none });
    expect(await screen.findByTestId('home-hero')).toBeTruthy();
    expect(screen.queryByText('Rewards near you')).toBeNull();
  });

  it('is in French', async () => {
    french();
    await start({ welcomed: true, url: '/home' });
    expect(await screen.findByText('Récompenses près de chez vous')).toBeTruthy();
  });
});
