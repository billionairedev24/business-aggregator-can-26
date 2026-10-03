// S-119 KDS: kitchen display screens at the dinner peak. Each VU is one screen of one kitchen holding the Studio's live
// stream (GET /api/v1/merchants/{id}/live, server-sent events, S-68) as the browser's EventSource does, and doing what
// the screen does on each `kitchen` event: refetch the board (/kitchen/live) and the nav badges. The screens also work
// the tickets — accept the new ones, mark them ready, hand them off; whoever taps first, the others get a 409 — so
// every ticket sends update events too. The tickets themselves come from the checkout_food scenario's customers.
//
// kds_ticket_delivery: from the order being placed (the board's placedAt; else the time in the order id's ULID) to its
// first event on this screen — the KDS ticket-delivery SLI end to end, transport included. kds_stream_ready: from
// opening the stream to its `ready` event (the freshness side: a stream that opens slowly is a stale screen).
import sse from 'k6/x/sse';
import { check } from 'k6';
import exec from 'k6/execution';
import { API_URL } from '../lib/config.js';
import { data, flow, get, json, post, ulidTime, who } from '../lib/api.js';
import { kdsEvents, kdsStreamReady, kdsTicketDelivery } from '../lib/slo.js';
import { kitchensWithScreens } from '../lib/kitchens.js';
import { SCREENS_PER_KITCHEN } from '../lib/profiles.js';

/**
 * How long one stream stays open before the screen reconnects (seconds; the server ends it after LIVE_STREAM, 10 min).
 * Checked on every event — the server's keep-alive comes every 25 s — and the stream closed from the client: xk6-sse's
 * own `timeout` stops the reading but leaves the VU stuck (S-119's first runs).
 */
const STREAM_S = Number(__ENV.KDS_STREAM_S || 120);
/** A ticket is marked ready this long after it was placed, and handed off twice as long after (seconds). */
const COOK_S = Number(__ENV.KDS_COOK_S || 20);

/** An ISO-8601 instant with microseconds (as the api writes them) in epoch milliseconds. */
function instant(iso) {
  return Date.parse(iso.replace(/(\.\d{3})\d+/, '$1'));
}

function board(kitchen, owner) {
  return json(get(`/api/v1/merchants/${kitchen}/kitchen/live`, owner,
    { name: '/api/v1/merchants/{id}/kitchen/live', flow: 'kds' }));
}

function work(kitchen, owner, tickets) {
  const now = Date.now();
  for (const t of tickets) {
    const age = now - instant(t.placedAt);
    let step = null;
    if (t.stage === 'new') step = 'accept';
    else if (t.stage === 'cooking' && age > COOK_S * 1000) step = 'ready';
    else if (t.stage === 'ready' && age > 2 * COOK_S * 1000) step = 'handoff';
    if (!step) continue;
    const res = post(`/api/v1/merchants/${kitchen}/kitchen/live/${t.orderId}/${step}`, {}, owner,
      { name: `/api/v1/merchants/{id}/kitchen/live/{orderId}/${step}`, flow: 'kds', expected: [200, 409] });
    flow(check(res, { [`ticket ${step}`]: r => r.status === 200 || r.status === 409 }), `kds ${step}`, res);
  }
}

export function kdsScreen() {
  const kitchen = data.kitchens[(exec.vu.idInTest - 1) % kitchensWithScreens(data.kitchens.length)];
  // whoever taps first: each of a kitchen's screens works a ticket it sees with this chance (the rest answer 409)
  const lead = () => Math.random() < 1 / SCREENS_PER_KITCHEN;
  const owner = kitchen.ownerId;
  const seen = new Set();
  const opened = Date.now();
  let ready = false;

  const refresh = () => {
    const b = board(kitchen.merchantId, owner);
    get(`/api/v1/merchants/${kitchen.merchantId}/nav-badges`, owner,
      { name: '/api/v1/merchants/{id}/nav-badges', flow: 'kds' });
    return b;
  };

  const res = sse.open(`${API_URL}/api/v1/merchants/${kitchen.merchantId}/live`, {
    method: 'GET', headers: Object.assign({ Accept: 'text/event-stream' }, who(owner)), timeout: `${STREAM_S + 900}s`,
    tags: { name: '/api/v1/merchants/{id}/live', flow: 'kds' },
  }, client => {
    client.on('event', event => {
      kdsEvents.add(1, { event: event.name || 'keep-alive' });
      if (Date.now() - opened > STREAM_S * 1000) {
        client.close(); // reconnect, as EventSource does when the server ends the stream
        return;
      }
      if (event.name === 'ready') {
        ready = true;
        kdsStreamReady.add(Date.now() - opened);
        const b = refresh();
        if (b && b.items) b.items.forEach(t => seen.add(t.orderId)); // already on the screen: not a delivery
        if (lead() && b && b.items) work(kitchen.merchantId, owner, b.items);
        return;
      }
      if (event.name !== 'kitchen') return;
      const ref = event.data ? (JSON.parse(event.data) || {}).ref : null;
      const receivedAt = Date.now();
      const b = refresh();
      if (ref && !seen.has(ref)) {
        seen.add(ref);
        const ticket = b && b.items ? b.items.find(t => t.orderId === ref) : null;
        const placedAt = ticket && ticket.placedAt ? instant(ticket.placedAt) : ulidTime(ref);
        kdsTicketDelivery.add(Math.max(0, receivedAt - placedAt));
      }
      if (lead() && b && b.items) work(kitchen.merchantId, owner, b.items);
    });
    client.on('error', () => {}); // closing the stream on purpose lands here
  });
  flow(check(res, { 'live stream opened': r => r && r.status === 200 }) && ready, 'kds stream', res);
  // like the board's safety refresh after a reconnect: tickets that moved without an event on this stream
  if (lead()) {
    const b = board(kitchen.merchantId, owner);
    if (b && b.items) work(kitchen.merchantId, owner, b.items);
  }
}
