import type { GroupView, Restaurant } from '../api/group';
import type { FixtureArea, FixtureContext } from './context';

/**
 * S-159 group food orders as far as the app sees them: one group at a made-up kitchen ({@link FIXTURE_GROUP_CODE}),
 * hosted by someone else, split bill. Joining, adding a dish, the host's lock (done by the test through the state),
 * paying one's share (the fake gateway: no card), and the 404 of an unknown code.
 */
export const FIXTURE_GROUP_CODE = '7Q2M-K4XP';

export interface GroupFixtureState {
  group: GroupView;
  menu: Restaurant;
}

export function newGroupState(): GroupFixtureState {
  return {
    menu: {
      kitchen: { name: 'Sample Kitchen' },
      sections: [{
        id: 's-1', name: 'Bowls', items: [
          { id: 'd-pho', name: 'Pho tai', description: 'Rare beef', priceCents: 1600, soldOut: false, availableNow: true, groups: [
            { id: 'g-size', name: 'Size', rule: 'exactly', count: 1, required: true, showForOptionIds: [], options: [
              { id: 'o-reg', name: 'Regular', deltaCents: 0, isDefault: true, soldOut: false },
              { id: 'o-lg', name: 'Large', deltaCents: 300, isDefault: false, soldOut: false },
            ] },
          ] },
          { id: 'd-rolls', name: 'Spring rolls', priceCents: 800, soldOut: false, availableNow: true, groups: [] },
        ],
      }],
    },
    group: {
      id: 'grp-1', code: FIXTURE_GROUP_CODE, state: 'open', payment: 'split', merchantId: 'k-1', kitchen: 'Sample Kitchen', kitchenSlug: 'sample-kitchen',
      mode: 'delivery', area: 'Old Town', hostId: 'host-1', you: '', host: false, subtotalCents: 800, groupMax: 12, tip: { kind: 'none', value: 0 },
      orderId: null, ref: null, canPlace: false, tracking: null, expiresAt: '2026-10-22T17:00:00Z',
      members: [{ userId: 'host-1', name: 'H. Host', host: true, subtotalCents: 800, share: null, items: [
        { id: 'i-1', itemId: 'd-rolls', title: 'Spring rolls', qty: 1, unitCents: 800, totalCents: 800, choices: [] },
      ] }],
    },
  };
}

export function groupFixtures(ctx: FixtureContext, state: GroupFixtureState, who: () => { id: string; firstName: string; lastName: string } | null): FixtureArea {
  const view = () => {
    const me = who();
    const g = state.group;
    g.you = me?.id ?? '';
    g.host = g.hostId === g.you;
    g.subtotalCents = g.members.reduce((n, m) => n + m.subtotalCents, 0);
    g.canPlace = g.payment === 'split' && g.members.every((m) => !m.share || m.share.state === 'authorized') && g.members.some((m) => m.share);
    return ctx.answer(200, g);
  };
  return (req) => {
    const p = req.path;
    if (p === '/public/kitchens/sample-kitchen' && req.method === 'GET') return ctx.answer(200, state.menu);
    if (!p.startsWith('/me/group-orders')) return undefined;
    const me = who();
    if (!me) return ctx.answer(401, { code: 'unauthorized' });
    const g = state.group;
    const mine = () => g.members.find((m) => m.userId === me.id);
    // the api normalises a typed code ("7q2m k4xp")
    if (p.startsWith('/me/group-orders/join/') && decodeURIComponent(p.slice(22)).toUpperCase().replace(/[^0-9A-Z]/g, '') === g.code.replace('-', '')) {
      return ctx.answer(200, { code: g.code, kitchen: g.kitchen, kitchenSlug: g.kitchenSlug, hostFirstName: 'Hana', state: g.state, people: g.members.length, groupMax: g.groupMax, groupId: mine() ? g.id : null });
    }
    if (p.startsWith('/me/group-orders/join/')) return ctx.answer(404, { code: 'not_found', detail: 'No group order with that code.' });
    if (p === '/me/group-orders/join' && req.method === 'POST') {
      if (!mine()) g.members.push({ userId: me.id, name: `${me.firstName[0]}. ${me.lastName}`, host: false, items: [], subtotalCents: 0, share: null });
      return view();
    }
    if (p === `/me/group-orders/${g.id}` && req.method === 'GET') return mine() ? view() : ctx.answer(404, { code: 'not_found' });
    if (p === `/me/group-orders/${g.id}/items` && req.method === 'POST') {
      if (g.state !== 'open') return ctx.answer(409, { code: 'group_locked', detail: 'The host has locked this order. Ask them to unlock it to make changes.' });
      const dish = state.menu.sections.flatMap((s) => s.items).find((d) => d.id === req.body.itemId);
      if (!dish) return ctx.answer(409, { code: 'item_unavailable', detail: 'That dish is not available.' });
      const optionIds = (req.body.optionIds as string[] | undefined) ?? [];
      const options = dish.groups.flatMap((x) => x.options).filter((o) => optionIds.includes(o.id));
      const unit = dish.priceCents + options.reduce((n, o) => n + o.deltaCents, 0);
      const qty = Number(req.body.qty ?? 1);
      const m = mine()!;
      m.items.push({ id: `i-${Date.now()}-${m.items.length}`, itemId: dish.id, title: dish.name, qty, unitCents: unit, totalCents: unit * qty, choices: options.map((o) => o.name) });
      m.subtotalCents = m.items.reduce((n, i) => n + i.totalCents, 0);
      return view();
    }
    const share = /^\/me\/group-orders\/[^/]+\/shares\/([^/]+)\/(pay|confirm)$/.exec(p);
    if (share && req.method === 'POST') {
      const m = g.members.find((x) => x.userId === decodeURIComponent(share[1]!));
      if (!m?.share) return ctx.answer(404, { code: 'not_found' });
      if (share[2] === 'pay') {
        m.share.state = 'paying';
        g.state = 'paying';
        return ctx.answer(201, { shareId: `sh-${m.userId}`, totalCents: m.share.totalCents, paymentIntent: 'pi_fake_share', clientSecret: null, status: 'authorized', mode: 'fake', publishableKey: null });
      }
      m.share.state = 'authorized';
      return view();
    }
    if (p === `/me/group-orders/${g.id}/leave` && req.method === 'POST') {
      g.members = g.members.filter((x) => x.userId !== me.id);
      return ctx.answer(200, { ...g, you: me.id, host: false });
    }
    return ctx.answer(404, { code: 'not_found' });
  };
}
