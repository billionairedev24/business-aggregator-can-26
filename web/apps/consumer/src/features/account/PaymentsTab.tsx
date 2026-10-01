import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, DataTable, Dialog, EmptyState, ErrorState, Tag, useFormatters, useLocale, type DataTableColumn } from '@northline/ui';
import { shortDate } from './format';
import { CardForm } from './CardForm';
import { FormSkeleton } from './ProfileTab';
import { billingQuery, cardsQuery, useDefaultCard, useRemoveCard, type Payment, type SavedCard } from './settingsApi';
import { useSettingsT, type SettingsT } from './settingsMessages';

const BRANDS: Record<string, string> = { visa: 'Visa', mastercard: 'Mastercard', amex: 'Amex', discover: 'Discover', jcb: 'JCB', diners: 'Diners', unionpay: 'UnionPay' };
export const brandName = (code: string) => BRANDS[code] ?? (code ? code.charAt(0).toUpperCase() + code.slice(1) : '');
export const cardName = (c: Pick<SavedCard, 'brand' | 'last4'>, t: SettingsT) => t('cardName', { brand: brandName(c.brand), last4: c.last4 });
const exp = (c: SavedCard) => `${String(c.expMonth).padStart(2, '0')}/${String(c.expYear).slice(-2)}`;

/** Payment methods (design 06 `at.payments`): saved cards, Add payment method (SetupIntent), billing history. */
export function PaymentsTab() {
  const t = useSettingsT();
  return (
    <>
      <h1 id="acct-title" className="nl-acct-h1">{t('payTitle')}</h1>
      <p className="nl-acct-lede">{t('payLede')}</p>
      <CardList detailed />
      <h2 className="nl-acct-h2">{t('billing')}</h2>
      <Billing />
    </>
  );
}

/** The saved cards (also the wallet's "Payment methods" section) with Make default / Remove and the add form. */
export function CardList({ detailed = false }: { detailed?: boolean }) {
  const t = useSettingsT();
  const { locale } = useLocale();
  const cards = useQuery(cardsQuery);
  const makeDefault = useDefaultCard();
  const remove = useRemoveCard();
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<SavedCard>();
  if (cards.isPending) return <FormSkeleton label={t('loading')} rows={2} />;
  if (cards.isError) return <ErrorState message={t('loadError')} onRetry={() => void cards.refetch()} />;
  const items = cards.data.items;
  return (
    <>
      {items.length === 0 && !adding ? <EmptyState>{t('noCards')}</EmptyState> : (
        <ul className="nl-pm-list" aria-label={t('payTitle')}>
          {items.map(c => (
            <li key={c.id} className="nl-pm">
              <span>
                <strong>{cardName(c, t)}</strong>
                <span className="nl-small nl-muted nl-block">{detailed ? t('cardSubAdded', { exp: exp(c), added: shortDate(c.addedAt, locale) }) : t('cardSub', { exp: exp(c) })}</span>
              </span>
              <span className="nl-pm-actions">
                {c.isDefault ? <Tag tone="accent">{t('default')}</Tag>
                  : <Button type="button" variant="ghost" aria-label={t('makeDefaultCard', { card: cardName(c, t) })} disabled={makeDefault.isPending} onClick={() => makeDefault.mutate(c.id)}>{t('makeDefault')}</Button>}
                <Button type="button" variant="ghost" aria-label={t('removeCard', { card: cardName(c, t) })} onClick={() => setRemoving(c)}>{t('remove')}</Button>
              </span>
            </li>
          ))}
        </ul>
      )}
      {makeDefault.isError || remove.isError ? <p className="nl-error" role="alert">{t('saveError')}</p> : null}
      <Button type="button" variant="secondary" className="nl-pm-add" aria-expanded={adding} onClick={() => setAdding(a => !a)}>{adding ? t('addPmCancel') : t(detailed ? 'addPm' : 'addCard')}</Button>
      {adding ? <CardForm onSaved={() => setAdding(false)} /> : null}
      <Dialog open={!!removing} onClose={() => setRemoving(undefined)} title={removing ? t('removeCard', { card: cardName(removing, t) }) : ''} role="alertdialog"
        actions={<>
          <Button type="button" variant="ghost" onClick={() => setRemoving(undefined)}>{t('cancel')}</Button>
          <Button type="button" disabled={remove.isPending} onClick={() => removing && remove.mutate(removing.id, { onSuccess: () => setRemoving(undefined) })}>{t('remove')}</Button>
        </>}>
        <p>{removing ? t('removeConfirm', { card: cardName(removing, t) }) : null}</p>
      </Dialog>
    </>
  );
}

interface BillRow { id: string; date: string; what: string; method: string; amount: number }

function Billing() {
  const t = useSettingsT();
  const { locale } = useLocale();
  const { money } = useFormatters();
  const billing = useQuery(billingQuery);
  const columns = useMemo<DataTableColumn<BillRow>[]>(() => [
    { key: 'date', label: t('colDate'), filter: false },
    { key: 'what', label: t('colWhat'), primary: true, filter: false },
    { key: 'method', label: t('colMethod'), filter: false },
    { key: 'amount', label: t('colAmount'), type: 'num', filter: false, format: v => money(Number(v)) },
  ], [t, money]);
  if (billing.isPending) return <FormSkeleton label={t('loading')} rows={3} />;
  if (billing.isError) return <ErrorState message={t('loadError')} onRetry={() => void billing.refetch()} />;
  if (billing.data.length === 0) return <EmptyState>{t('billingEmpty')}</EmptyState>;
  const rows: BillRow[] = billing.data.map((p: Payment) => {
    const what = p.what === 'delivery' ? t('what_delivery') : p.what;
    const status = t(`st_${p.status}` as Parameters<SettingsT>[0]);
    return {
      id: p.id,
      date: shortDate(p.at, locale),
      what: [what, p.ref, status.startsWith('st_') ? null : status].filter(Boolean).join(' · '),
      method: p.cardBrand && p.cardLast4 ? cardName({ brand: p.cardBrand, last4: p.cardLast4 }, t) : t('card'),
      amount: p.amountCents,
    };
  });
  return (
    <div className="nl-acct-table">
      <DataTable<BillRow> entity={t('paymentEntity')} plural={t('paymentPlural')} aria-label={t('billing')} columns={columns} rows={rows}
        can={{ create: false, update: false, delete: false, export: true }} />
    </div>
  );
}
