import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';

import { ApiError, colors, fonts, radius, semantic, space, type PickedImage } from '@northline/mobile-kit';

import { CANCEL_REASONS, RETURN_REASONS, type OrderReturn, type OrderTracking, type Returnable } from '../api/shop';
import type { MessageKey } from '../i18n';
import { pickImage } from '../photos/pick';
import { shop, useShopFormat } from '../shop/common';
import { Chip, Kicker, Panel } from '../shop/parts';
import { Body, Button, Field, Notice, type } from '../ui/primitives';
import { errorMessage } from '../ui/states';

const RETURN_PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const RETURN_PHOTO_MAX = 5 * 1024 * 1024;

type T = ReturnType<typeof useShopFormat>['t'];
/** The api's words for a refusal (a 409's detail, a 422's messages), else ours. */
const problem = (e: unknown, t: T) => (e instanceof ApiError && e.errors.length ? e.errors.map((x) => x.message).join(' ') : errorMessage(e, t));

function useOrderUpdate(orderId: string) {
  const qc = useQueryClient();
  return (o: OrderTracking | null | undefined) => {
    if (o) qc.setQueryData(['shop', 'order', orderId], o);
    void qc.invalidateQueries({ queryKey: ['shop', 'order', orderId] });
  };
}

/** S-151: what was cancelled, in the customer's words. */
export function Cancellations({ order }: { order: OrderTracking }) {
  const { t } = useShopFormat();
  const list = order.cancellations ?? [];
  if (!list.length) return null;
  return (
    <View style={styles.gap} testID="cancellations">
      {list.map((c) => (
        <Body key={c.at + c.by} tone="muted">
          {c.reason === 'delivery_failed'
            ? t(c.by === 'customer' ? 'as.cancelled.afterMiss' : 'as.cancelled.system')
            : c.by === 'customer'
              ? t('as.cancelled.customer')
              : c.by === 'merchant'
                ? c.whole && !c.merchantName
                  ? t('as.cancelled.merchantWhole')
                  : t('as.cancelled.merchant', { shop: c.merchantName ?? '' })
                : t('as.cancelled.staff')}
        </Body>
      ))}
    </View>
  );
}

/** S-151: "Cancel order" while no shop has packed it (`canCancel`) — a reason, an optional note. */
export function CancelOrder({ order }: { order: OrderTracking }) {
  const { t } = useShopFormat();
  const update = useOrderUpdate(order.orderId);
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<string>();
  const [note, setNote] = useState('');
  const [error, setError] = useState<string>();
  const cancel = useMutation({ mutationFn: (b: { reason: string; note?: string }) => shop().cancelOrder(order.orderId, b), onSuccess: update });
  if (!order.canCancel) return null;
  if (!open) return <Button label={t('as.cancel')} tone="secondary" onPress={() => setOpen(true)} testID="cancel-open" />;
  const submit = () => {
    if (!reason) return setError(t('as.chooseReason'));
    setError(undefined);
    cancel.mutate({ reason, ...(note.trim() ? { note: note.trim() } : {}) }, { onError: (e) => setError(problem(e, t)) });
  };
  return (
    <Panel testID="cancel-order">
      <View style={styles.gap}>
        <Text accessibilityRole="header" style={[type.body, type.strong]}>{t('as.cancel.title')}</Text>
        <Body tone="small">{t('as.cancel.lede')}</Body>
        <Kicker>{t('as.cancel.reason')}</Kicker>
        <View style={styles.chips} accessibilityRole="radiogroup">
          {CANCEL_REASONS.map((r) => (
            <Chip key={r} role="radio" label={t(`as.cr.${r}` as MessageKey)} on={reason === r} onPress={() => setReason(r)} testID={`cancel-reason-${r}`} />
          ))}
        </View>
        <Field label={t('as.note')} value={note} onChangeText={setNote} multiline maxLength={500} testID="cancel-note" />
        {error ? <Notice message={error} testID="cancel-error" /> : null}
        <Button label={t('as.cancel.confirm')} tone="danger" busy={cancel.isPending} onPress={submit} testID="cancel-confirm" />
        <Button label={t('as.cancel.keep')} tone="ghost" onPress={() => setOpen(false)} />
      </View>
    </Panel>
  );
}

/** S-151: the courier couldn't hand it over — why, until when, and re-delivery on the next run or a refund. */
export function FailedDelivery({ order }: { order: OrderTracking }) {
  const f = useShopFormat();
  const { t } = f;
  const update = useOrderUpdate(order.orderId);
  const [error, setError] = useState<string>();
  const choose = useMutation({ mutationFn: (c: 'redeliver' | 'refund') => shop().failureChoice(order.orderId, c), onSuccess: update });
  const d = order.deliveryFailure;
  if (!d) return null;
  if (d.choice === 'redeliver') return <Notice tone="info" message={t('as.failed.redelivering')} testID="failed-redeliver" />;
  if (d.choice) return null;
  const pick = (c: 'redeliver' | 'refund') => {
    setError(undefined);
    choose.mutate(c, { onError: (e) => setError(problem(e, t)) });
  };
  return (
    <Panel testID="failed-delivery">
      <View style={styles.gap}>
        <Text accessibilityRole="header" style={[type.body, type.strong]}>{t('as.failed.title')}</Text>
        <Body>{t(`as.fr.${d.reason}` as MessageKey)}</Body>
        <Body tone="small">{t('as.failed.back', { date: `${f.date(d.decideBy)} ${f.time(d.decideBy)}` })}</Body>
        {d.feeCents > 0 ? <Body tone="small">{t('as.failed.fee', { fee: f.money(d.feeCents) })}</Body> : null}
        {error ? <Notice message={error} testID="failed-error" /> : null}
        <Button label={t('as.failed.redeliver')} busy={choose.isPending && choose.variables === 'redeliver'} disabled={choose.isPending} onPress={() => pick('redeliver')} testID="failed-redeliver-button" />
        <Button label={t('as.failed.refund')} tone="secondary" busy={choose.isPending && choose.variables === 'refund'} disabled={choose.isPending} onPress={() => pick('refund')} testID="failed-refund-button" />
      </View>
    </Panel>
  );
}

/** S-151: the order's returns, and the return form while something is still in its window. */
export function Returns({ order }: { order: OrderTracking }) {
  const { t } = useShopFormat();
  const returns = order.returns ?? [];
  const returnable = order.returnable ?? [];
  if (!returns.length && !returnable.length) return null;
  return (
    <View style={styles.gap} testID="returns">
      <Text accessibilityRole="header" style={[type.body, type.strong]}>{t('as.returns')}</Text>
      {returns.map((r) => <ReturnRow key={r.id} orderId={order.orderId} r={r} />)}
      {returnable.length ? <ReturnForm orderId={order.orderId} lines={returnable} /> : null}
    </View>
  );
}

function ReturnRow({ orderId, r }: { orderId: string; r: OrderReturn }) {
  const f = useShopFormat();
  const { t } = f;
  const qc = useQueryClient();
  const [asking, setAsking] = useState(false);
  const [statement, setStatement] = useState('');
  const [error, setError] = useState<string>();
  const act = useMutation({
    mutationFn: (a: { kind: 'withdraw' } | { kind: 'escalate'; statement: string }) =>
      a.kind === 'withdraw' ? shop().withdrawReturn(r.id) : shop().escalateReturn(r.id, a.statement),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['shop', 'order', orderId] }),
    onError: (e) => setError(problem(e, t)),
  });
  const shopName = r.merchantName ?? '';
  const state =
    r.state === 'approved'
      ? t(r.method === 'pickup' ? 'as.rs.approved_pickup' : 'as.rs.approved_dropoff', { shop: shopName })
      : t(`as.rs.${r.state}` as MessageKey, { shop: shopName, amount: f.money(r.amountCents) });
  return (
    <Panel testID={`return-${r.id}`}>
      <View style={styles.gapSmall}>
        <Text style={[type.body, type.strong]}>
          {r.ref} · {r.lines.map((l) => (l.qty > 1 ? `${l.title} ×${l.qty}` : l.title)).join(', ')}
        </Text>
        <Body tone="small">{state}</Body>
        {r.state === 'declined' && r.declineReason ? <Body tone="small">{t('as.return.declined', { reason: r.declineReason })}</Body> : null}
        {error ? <Notice message={error} /> : null}
        {r.canWithdraw ? <Button label={t('as.return.withdraw')} tone="ghost" busy={act.isPending} onPress={() => { setError(undefined); act.mutate({ kind: 'withdraw' }); }} testID={`return-withdraw-${r.id}`} /> : null}
        {r.canEscalate && !asking ? <Button label={t('as.return.escalate')} tone="secondary" onPress={() => setAsking(true)} testID={`return-escalate-${r.id}`} /> : null}
        {asking ? (
          <>
            <Field label={t('as.return.statement')} value={statement} onChangeText={setStatement} multiline maxLength={1000} testID={`return-statement-${r.id}`} />
            <Button
              label={t('as.return.sendToAgent')}
              busy={act.isPending}
              onPress={() => {
                setError(undefined);
                if (!statement.trim()) return setError(t('as.return.statementNeeded'));
                act.mutate({ kind: 'escalate', statement: statement.trim() });
              }}
              testID={`return-send-${r.id}`}
            />
          </>
        ) : null}
      </View>
    </Panel>
  );
}

function ReturnForm({ orderId, lines }: { orderId: string; lines: Returnable[] }) {
  const f = useShopFormat();
  const { t } = f;
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [chosen, setChosen] = useState<string[]>([]);
  const [reason, setReason] = useState<string>();
  const [note, setNote] = useState('');
  const [photo, setPhoto] = useState<PickedImage>();
  const [errors, setErrors] = useState<{ lines?: string; reason?: string; photo?: string; form?: string }>({});
  const send = useMutation({
    mutationFn: (form: FormData) => shop().requestReturn(orderId, form),
    onSuccess: () => {
      setOpen(false);
      setChosen([]);
      setReason(undefined);
      setNote('');
      setPhoto(undefined);
      void qc.invalidateQueries({ queryKey: ['shop', 'order', orderId] });
    },
    onError: (e) => setErrors({ form: problem(e, t) }),
  });
  if (!open) return <Button label={t('as.return.items')} tone="secondary" onPress={() => setOpen(true)} testID="return-open" />;
  const addPhoto = async (source: 'camera' | 'library') => {
    const picked = await pickImage(source, 0.7).catch(() => null);
    if (picked === 'denied') return setErrors((e) => ({ ...e, photo: t('as.return.cameraDenied') }));
    if (!picked) return;
    const mime = picked.mimeType ?? '';
    if (!RETURN_PHOTO_TYPES.includes(mime) || (picked.fileSize != null && picked.fileSize > RETURN_PHOTO_MAX)) {
      return setErrors((e) => ({ ...e, photo: t('as.return.photoFile') }));
    }
    setPhoto(picked);
    setErrors(({ photo: _, ...rest }) => rest);
  };
  const submit = () => {
    const next: typeof errors = {};
    const shops = new Set(lines.filter((l) => chosen.includes(l.lineId)).map((l) => l.merchantId));
    if (!chosen.length) next.lines = t('as.return.chooseItems');
    else if (shops.size > 1) next.lines = t('as.return.oneShop');
    if (!reason) next.reason = t('as.chooseReason');
    if (!photo) next.photo = t('as.return.addPhoto');
    setErrors(next);
    if (Object.keys(next).length || !photo || !reason) return;
    const form = new FormData();
    chosen.forEach((l) => form.append('lineIds', l));
    form.append('reason', reason);
    if (note.trim()) form.append('note', note.trim());
    const mime = photo.mimeType ?? 'image/jpeg';
    const ext = mime === 'image/png' ? 'png' : mime === 'image/webp' ? 'webp' : 'jpg';
    form.append('photo', { uri: photo.uri, type: mime, name: `return.${ext}` } as unknown as Blob);
    send.mutate(form);
  };
  return (
    <Panel testID="return-form">
      <View style={styles.gap}>
        <Body tone="small">{t('as.return.lede')}</Body>
        <Kicker>{t('as.return.items')}</Kicker>
        {lines.map((l) => {
          const on = chosen.includes(l.lineId);
          const title = l.qty > 1 ? `${l.title} ×${l.qty}` : l.title;
          return (
            <Pressable
              key={l.lineId}
              accessibilityRole="checkbox"
              accessibilityLabel={`${title}, ${f.money(l.amountCents)}`}
              accessibilityState={{ checked: on }}
              onPress={() => setChosen((xs) => (on ? xs.filter((x) => x !== l.lineId) : [...xs, l.lineId]))}
              style={[styles.item, on && styles.itemOn]}
              testID={`return-line-${l.lineId}`}
            >
              <View style={[styles.box, on && styles.boxOn]} />
              <View style={styles.flex}>
                <Text style={type.body}>{title}</Text>
                <Text style={styles.small}>{t('as.return.until', { date: f.date(l.returnUntil) })}</Text>
              </View>
              <Text style={styles.small}>{f.money(l.amountCents)}</Text>
            </Pressable>
          );
        })}
        {errors.lines ? <Text accessibilityRole="alert" style={styles.error}>{errors.lines}</Text> : null}
        <Kicker>{t('as.return.reason')}</Kicker>
        <View style={styles.chips} accessibilityRole="radiogroup">
          {RETURN_REASONS.map((r) => (
            <Chip key={r} role="radio" label={t(`as.rr.${r}` as MessageKey)} on={reason === r} onPress={() => setReason(r)} testID={`return-reason-${r}`} />
          ))}
        </View>
        {errors.reason ? <Text accessibilityRole="alert" style={styles.error}>{errors.reason}</Text> : null}
        <Kicker>{t('as.return.photo')}</Kicker>
        {photo ? <Image source={{ uri: photo.uri }} style={styles.photo} accessibilityLabel={t('as.return.photoAdded')} testID="return-photo" /> : null}
        <View style={styles.chips}>
          <Button label={t('as.return.takePhoto')} tone="secondary" onPress={() => void addPhoto('camera')} testID="return-photo-camera" />
          <Button label={t('as.return.choosePhoto')} tone="secondary" onPress={() => void addPhoto('library')} testID="return-photo-library" />
        </View>
        {errors.photo ? <Text accessibilityRole="alert" style={styles.error}>{errors.photo}</Text> : null}
        <Field label={t('as.note')} value={note} onChangeText={setNote} multiline maxLength={1000} testID="return-note" />
        {errors.form ? <Notice message={errors.form} testID="return-error" /> : null}
        <Button label={t('as.return.send')} busy={send.isPending} onPress={submit} testID="return-send" />
      </View>
    </Panel>
  );
}

const styles = StyleSheet.create({
  gap: { gap: space[3] },
  gapSmall: { gap: space[1] },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
  flex: { flex: 1 },
  item: { flexDirection: 'row', alignItems: 'center', gap: space[3], padding: space[3], borderRadius: radius.lg, borderWidth: 1, borderColor: semantic['border-card'] },
  itemOn: { borderColor: semantic['action-primary'] },
  box: { width: 20, height: 20, borderRadius: 4, borderWidth: 2, borderColor: colors.textMuted },
  boxOn: { backgroundColor: semantic['action-primary'], borderColor: semantic['action-primary'] },
  photo: { height: 160, borderRadius: radius.lg, backgroundColor: semantic['surface-sunken'] },
  small: { fontFamily: fonts.regular, fontSize: 13, color: colors.textMuted },
  error: { fontFamily: fonts.body, fontSize: 13, color: colors.accent2_700 },
});
