import { useQuery, useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useRef, useState } from 'react';
import { Share, View } from 'react-native';

import { ApiError, randomId } from '@northline/mobile-kit';

import { groupApi, type GroupView, type MenuDish, type ShareStarted, type Tip } from '../api/group';
import type { Started } from '../api/shop';
import { useAuth } from '../auth/AuthProvider';
import { config } from '../config';
import { services } from '../services';
import { useShopFormat } from '../shop/common';
import { Chip } from '../shop/parts';
import { cardPaymentsFor } from '../shop/payments';
import { StepUpFailed, stepUpWithCode } from '../shop/stepUp';
import { Body, Button, Field, Notice, Tag, Title } from '../ui/primitives';
import { Screen } from '../ui/screen';
import { LoadingList, SignInPrompt, errorMessage } from '../ui/states';
import { Heading, accountStyles } from '../account/parts';

const groups = () => groupApi(services().api);
const KEY = (id: string) => ['group', id] as const;
const TIPS: readonly Tip[] = [{ kind: 'none', value: 0 }, { kind: 'amount', value: 200 }, { kind: 'amount', value: 400 }, { kind: 'percent', value: 15 }];

/**
 * A group food order in the app (S-159; the consumer web's /food/group/<code>): someone signed in joins from the code
 * (typed, or the link's last part), sees everyone's dishes, adds their own from the kitchen's menu while the cart is
 * open, and pays their share (split bill: the native card sheet, or nothing to collect with the api's stand-in); the
 * host also locks the cart, pays for everyone or for a guest, and sends it to the kitchen — one ticket, one delivery.
 * Polls every 5 s so everyone sees the others' changes and payments.
 */
export function GroupOrder({ code }: { code: string }) {
  const f = useShopFormat();
  const { t } = f;
  const { status } = useAuth();
  const signedIn = status === 'signedIn';
  const qc = useQueryClient();
  const preview = useQuery({ queryKey: ['group', 'preview', code.toUpperCase()], queryFn: () => groups().preview(code), enabled: signedIn, retry: false });
  const [joined, setJoined] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!signedIn) {
    return (
      <Screen title={t('title.group')} testID="group-order">
        <SignInPrompt message={t('group.signIn')} />
      </Screen>
    );
  }
  if (preview.isPending) {
    return <Screen title={t('title.group')} testID="group-order"><LoadingList rows={3} height={44} /></Screen>;
  }
  if (preview.isError) {
    return (
      <Screen title={t('title.group')} testID="group-order">
        <Notice message={preview.error instanceof ApiError && preview.error.status === 404 ? t('group.notFound') : errorMessage(preview.error, t)} />
        <Button label={t('group.codeTitle')} tone="secondary" onPress={() => router.replace('/group')} />
      </Screen>
    );
  }
  const p = preview.data;
  const id = joined ?? p.groupId ?? null;
  if (id) return <GroupPanel id={id} />;

  const join = async () => {
    setBusy(true);
    setError(null);
    try {
      const view = await groups().join(p.code);
      qc.setQueryData(KEY(view.id), view);
      setJoined(view.id);
    } catch (e) {
      setError(errorMessage(e, t));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Screen title={t('title.group')} testID="group-order" footer={<Button label={t('group.join')} large busy={busy} onPress={() => void join()} testID="group-join" />}>
      {error ? <Notice message={error} /> : null}
      <Title>{t('group.joinTitle', { host: p.hostFirstName, kitchen: p.kitchen })}</Title>
    </Screen>
  );
}

type Paying = { kind: 'share'; userId: string; started: ShareStarted } | null;

function GroupPanel({ id }: { id: string }) {
  const f = useShopFormat();
  const { t } = f;
  const qc = useQueryClient();
  const q = useQuery({ queryKey: KEY(id), queryFn: () => groups().view(id), refetchInterval: 5_000 });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [tip, setTip] = useState(0);
  const [stepUp, setStepUp] = useState<{ retry: (proof: string) => Promise<void> } | null>(null);
  const [stepCode, setStepCode] = useState('');
  const keys = useRef({ start: randomId(), confirm: randomId() });

  if (q.isPending) return <Screen title={t('title.group')} testID="group-order"><LoadingList rows={3} height={44} /></Screen>;
  if (q.isError) return <Screen title={t('title.group')} testID="group-order"><Notice message={errorMessage(q.error, t)} /></Screen>;
  const g = q.data;
  const set = (v: GroupView) => qc.setQueryData(KEY(id), v);
  const open = g.state === 'open';
  const waiting = g.state === 'locked' || g.state === 'paying';

  const fail = (e: unknown, retry?: (proof: string) => Promise<void>) => {
    if (retry && e instanceof ApiError && e.code === 'step_up_required') return setStepUp({ retry });
    setError(errorMessage(e, t));
  };
  const act = async (work: () => Promise<GroupView>) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      set(await work());
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };
  /** One share's card payment: the native sheet under Stripe, nothing to collect with the api's stand-in. */
  const pay = (userId: string) => async (proof?: string) => {
    setBusy(true);
    setError(null);
    try {
      const started = await groups().payShare(id, userId, keys.current.start, proof);
      keys.current.start = randomId();
      await authorize({ kind: 'share', userId, started });
    } catch (e) {
      fail(e, (p) => pay(userId)(p));
    } finally {
      setBusy(false);
    }
  };
  const authorize = async (paying: NonNullable<Paying>) => {
    const s = paying.started;
    const cards = cardPaymentsFor(s.mode);
    if (cards) {
      const asStarted = {
        payment: { provider: s.mode, publishableKey: s.publishableKey ?? null },
        intents: [{ paymentIntent: s.paymentIntent, clientSecret: s.clientSecret ?? null, status: s.status, amountCents: s.totalCents }],
      } as unknown as Started;
      const r = await cards.pay(asStarted, { kind: 'new' });
      if (r.status !== 'paid') {
        if (r.status === 'failed') setError(r.message ?? t('group.error'));
        return;
      }
    }
    set(await groups().confirmShare(id, paying.userId, keys.current.confirm));
    keys.current.confirm = randomId();
  };
  const share = () => {
    const link = `${config.siteOrigin}/food/group/${encodeURIComponent(g.code)}`;
    void Share.share({ message: t('group.shareMessage', { kitchen: g.kitchen, link, code: g.code }) }).catch(() => undefined);
  };
  const confirmStepUp = async () => {
    if (!stepUp) return;
    try {
      const proof = await stepUpWithCode(stepCode);
      const retry = stepUp.retry;
      setStepUp(null);
      setStepCode('');
      await retry(proof);
    } catch (e) {
      setError(e instanceof StepUpFailed ? t('shop.stepUp.wrong') : errorMessage(e, t));
    }
  };

  const footer = (
    <>
      {error ? <Notice message={error} testID="group-error" /> : null}
      {notice ? <Notice tone="info" message={notice} /> : null}
      {open && g.kitchenSlug ? <Button label={t('group.menu')} tone="secondary" onPress={() => setMenuOpen(!menuOpen)} testID="group-menu" /> : null}
      {open && g.host ? <Button label={t('group.lock')} busy={busy} onPress={() => void act(() => groups().lock(id, g.mode === 'delivery' ? TIPS[tip]! : TIPS[0]!))} testID="group-lock" /> : null}
      {waiting && g.host && g.payment === 'split' && g.canPlace ? <Button label={t('group.send')} large busy={busy} onPress={() => void act(() => groups().place(id))} testID="group-place" /> : null}
      {waiting && g.host ? <Button label={t('group.unlock')} tone="ghost" onPress={() => void act(() => groups().unlock(id))} /> : null}
      {(open || waiting) && g.host ? <Button label={t('group.cancel')} tone="danger" onPress={() => void act(() => groups().cancel(id))} testID="group-cancel" /> : null}
      {open && !g.host ? <Button label={t('group.leave')} tone="ghost" onPress={() => void act(() => groups().leave(id)).then(() => router.back())} testID="group-leave" /> : null}
    </>
  );

  return (
    <Screen title={t('title.group')} testID="group-order" footer={footer}>
      <Title>{g.kitchen}</Title>
      <Body tone="muted">{[g.payment === 'split' ? t('group.paySplit') : t('group.payHost'), g.area ?? ''].filter(Boolean).join(' · ')}</Body>
      {g.state === 'placed' ? <Notice tone="info" message={t('group.placed', { ref: g.ref ?? '' })} /> : null}
      {g.state === 'cancelled' ? <Notice tone="info" message={t('group.cancelled')} /> : null}
      {g.state === 'expired' ? <Notice tone="info" message={t('group.expired')} /> : null}
      {waiting && !g.host ? <Notice tone="info" message={t('group.locked')} /> : null}
      {open ? <Button label={t('group.share')} tone="ghost" onPress={share} testID="group-share" /> : null}
      {stepUp ? (
        <View style={accountStyles.actions} testID="group-step-up">
          <Field label={t('account.data.authenticatorCode')} value={stepCode} onChangeText={setStepCode} keyboardType="number-pad" maxLength={6} testID="group-step-up-code" />
          <Button label={t('account.data.confirm')} onPress={() => void confirmStepUp()} />
        </View>
      ) : null}
      {menuOpen && g.kitchenSlug ? <Menu slug={g.kitchenSlug} onAdd={(dish, optionIds) => void act(() => groups().add(id, { itemId: dish.id, qty: 1, optionIds })).then(() => setNotice(t('group.added', { name: dish.name })))} /> : null}
      {open && g.host && g.mode === 'delivery' ? (
        <View style={accountStyles.chips} accessibilityRole="radiogroup">
          {TIPS.map((x, i) => (
            <Chip key={i} role="radio" on={tip === i} onPress={() => setTip(i)} label={x.kind === 'none' ? t('tip.none') : x.kind === 'percent' ? `${x.value}%` : f.money(x.value)} testID={`group-tip-${i}`} />
          ))}
        </View>
      ) : null}
      <View style={accountStyles.list}>
        {g.members.map((m) => (
          <View key={m.userId} style={accountStyles.actions} testID={`member-${m.userId}`}>
            <Heading>{m.userId === g.you ? t('group.you') : m.name}</Heading>
            {m.host ? <Tag label={t('group.host')} small /> : null}
            {m.items.length === 0 ? <Body tone="muted">{t('group.nothing')}</Body> : m.items.map((i) => (
              <Body key={i.id}>{`${i.qty}× ${i.title}${i.choices.length ? ` (${i.choices.join(', ')})` : ''} · ${f.money(i.totalCents)}`}</Body>
            ))}
            {m.share ? (
              m.share.state === 'authorized' ? <Tag label={m.share.paidBy ? `${t('group.paid')} · ${m.share.paidBy}` : t('group.paid')} tone="success" small />
                : m.share.state === 'voided' ? null
                : waiting && (m.userId === g.you || g.host) ? (
                  <Button label={m.userId === g.you ? t('group.payMine', { total: f.money(m.share.totalCents) }) : t('group.payFor', { name: m.name, total: f.money(m.share.totalCents) })} busy={busy} onPress={() => void pay(m.userId)()} testID={`pay-${m.userId}`} />
                ) : <Tag label={t('group.due')} small />
            ) : null}
          </View>
        ))}
      </View>
      {waiting && g.host && g.payment === 'split' && !g.canPlace ? <Body tone="small">{t('group.waiting', { count: g.members.filter((m) => m.share && m.share.state !== 'authorized').length })}</Body> : null}
    </Screen>
  );
}

/** The kitchen's dishes, a dish's required choices picked (the first option of each required group by default). */
function Menu({ slug, onAdd }: { slug: string; onAdd: (dish: MenuDish, optionIds: string[]) => void }) {
  const f = useShopFormat();
  const { t } = f;
  const menu = useQuery({ queryKey: ['group', 'menu', slug], queryFn: () => groups().menu(slug), staleTime: 30_000 });
  const [picked, setPicked] = useState<Record<string, string[]>>({});
  if (menu.isPending) return <LoadingList rows={2} height={44} />;
  if (menu.isError) return <Notice message={errorMessage(menu.error, t)} />;
  const choose = (dish: MenuDish, groupId: string, optionId: string, single: boolean) =>
    setPicked((p) => {
      const now = p[dish.id] ?? defaults(dish);
      const inGroup = dish.groups.find((x) => x.id === groupId)?.options.map((o) => o.id) ?? [];
      const rest = now.filter((o) => !inGroup.includes(o));
      const was = now.includes(optionId);
      return { ...p, [dish.id]: single ? [...rest, optionId] : was ? now.filter((o) => o !== optionId) : [...now, optionId] };
    });
  return (
    <View style={accountStyles.list} testID="group-menu-list">
      {menu.data.sections.flatMap((s) => s.items).filter((d) => !d.soldOut && d.availableNow).map((d) => {
        const options = picked[d.id] ?? defaults(d);
        const price = d.priceCents + d.groups.flatMap((x) => x.options).filter((o) => options.includes(o.id)).reduce((n, o) => n + o.deltaCents, 0);
        return (
          <View key={d.id} style={accountStyles.actions}>
            <Body>{d.name}</Body>
            {d.groups.map((grp) => (
              <View key={grp.id} style={accountStyles.chips} accessibilityLabel={t('group.choose', { group: grp.name })}>
                {grp.options.filter((o) => !o.soldOut).map((o) => (
                  <Chip key={o.id} role={grp.rule === 'exactly' && grp.count === 1 ? 'radio' : 'checkbox'} on={options.includes(o.id)} label={o.deltaCents ? `${o.name} +${f.money(o.deltaCents)}` : o.name} onPress={() => choose(d, grp.id, o.id, grp.rule === 'exactly' && grp.count === 1)} />
                ))}
              </View>
            ))}
            <Button label={t('group.add', { name: d.name, price: f.money(price) })} tone="secondary" onPress={() => onAdd(d, options)} testID={`add-${d.id}`} />
          </View>
        );
      })}
    </View>
  );
}

const defaults = (d: MenuDish) =>
  d.groups.flatMap((grp) => {
    const preset = grp.options.filter((o) => o.isDefault && !o.soldOut).map((o) => o.id);
    if (preset.length) return preset;
    return grp.required ? grp.options.filter((o) => !o.soldOut).slice(0, Math.max(1, grp.count)).map((o) => o.id) : [];
  });

/** "Join a group order": the code a friend sent (or the link's last part). */
export function GroupCode() {
  const { t } = useShopFormat();
  const [value, setValue] = useState('');
  return (
    <Screen title={t('title.group')} testID="group-code" footer={<Button label={t('group.open')} large disabled={!value.trim()} onPress={() => router.push(`/group/${encodeURIComponent(value.trim().split('/').pop() ?? '')}`)} testID="group-open" />}>
      <Title>{t('group.codeTitle')}</Title>
      <Field label={t('group.codeLabel')} value={value} onChangeText={setValue} autoCapitalize="characters" autoCorrect={false} testID="group-code-field" />
    </Screen>
  );
}
