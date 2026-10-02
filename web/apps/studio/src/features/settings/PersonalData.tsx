import { useState } from 'react';
import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { Button, defineMessages, Dialog, ErrorState, Skeleton, useFormatters } from '@northline/ui';
import { http } from '../../lib/http';
import { StepUpPanel } from '../finance/StepUpPanel';

/**
 * "Your personal data" (S-105) for team members and owners: a copy of what Northline holds about them, or deleting
 * their account — the same privacy requests as the consumer site (`/api/v1/me/privacy-requests`), confirmed with the
 * passkey or authenticator (X-Step-Up), handled under their province's law and its deadline. An owner who is the
 * business's only owner sees deletion held until ownership moves (the api reports it).
 */
const Request = z.object({
  id: z.string(), reference: z.string(), type: z.enum(['access', 'correction', 'erasure']),
  state: z.string(), law: z.object({ shortName: z.string() }), dueAt: z.string(), extendedTo: z.string().nullish(),
  scheduledFor: z.string().nullish(), export: z.object({ ready: z.boolean(), expiresAt: z.string().nullish() }).nullish(),
});
type Request = z.infer<typeof Request>;
const requestsQuery = queryOptions({
  queryKey: ['me', 'privacy-requests'],
  queryFn: async () => (await http('/api/v1/me/privacy-requests', {}, z.object({ items: z.array(Request) }))).items,
});
const OPEN = ['awaiting_verification', 'verified', 'in_progress'];

const useT = defineMessages({
  en: {
    title: 'Your personal data', lede: 'Ask for a copy of what Northline holds about you, or to delete your account. We answer within the time {privacyLaw} sets. Your business’s own records stay with the business.',
    download: 'Download my data', delete: 'Delete my account…', confirmTitle: 'Confirm it’s you',
    downloadIntro: 'Confirm with your passkey to ask for a copy of your data.', deleteIntro: 'Your account closes and your personal information is erased, except what the law makes us keep. If you are a business’s only owner, ownership must move first. You can cancel until the deletion starts.',
    confirm: 'Confirm', error: 'That didn’t work. Try again.', loadError: 'We couldn’t load your requests.',
    access: 'Copy of your data', erasure: 'Account deletion', correction: 'Correction',
    due: '{reference} · answer by {date} ({law})', preparing: 'Preparing…', ready: 'Ready', getData: 'Data (JSON)', getSummary: 'Summary', getLinks: 'Download',
    scheduled: 'Deletion on {date}', cancel: 'Cancel deletion', closed: '{reference} · {state}',
  },
  fr: {
    title: 'Vos renseignements personnels', lede: 'Demandez une copie de ce que Northline détient à votre sujet, ou la suppression de votre compte. Nous répondons dans le délai que fixe {privacyLaw}. Les dossiers de votre entreprise restent à l’entreprise.',
    download: 'Télécharger mes données', delete: 'Supprimer mon compte…', confirmTitle: 'Confirmez votre identité',
    downloadIntro: 'Confirmez avec votre clé d’accès pour demander une copie de vos données.', deleteIntro: 'Votre compte est fermé et vos renseignements personnels sont effacés, sauf ce que la loi nous oblige à garder. Si vous êtes le seul propriétaire d’une entreprise, la propriété doit d’abord être transférée. Vous pouvez annuler jusqu’au début de la suppression.',
    confirm: 'Confirmer', error: 'Ça n’a pas fonctionné. Réessayez.', loadError: 'Nous n’avons pas pu charger vos demandes.',
    access: 'Copie de vos données', erasure: 'Suppression du compte', correction: 'Correction',
    due: '{reference} · réponse d’ici le {date} ({law})', preparing: 'Préparation…', ready: 'Prête', getData: 'Données (JSON)', getSummary: 'Résumé', getLinks: 'Télécharger',
    scheduled: 'Suppression le {date}', cancel: 'Annuler la suppression', closed: '{reference} · {state}',
  },
});

export function PersonalData() {
  const t = useT();
  const { date } = useFormatters();
  const qc = useQueryClient();
  const requests = useQuery(requestsQuery);
  const [asking, setAsking] = useState<'access' | 'erasure'>();
  const [links, setLinks] = useState<Record<string, { url: string; summaryUrl: string }>>({});
  const refresh = () => qc.invalidateQueries({ queryKey: requestsQuery.queryKey });
  const open = useMutation({
    mutationFn: ({ type, proof }: { type: 'access' | 'erasure'; proof: string }) =>
      http('/api/v1/me/privacy-requests', { method: 'POST', body: { type }, headers: { 'x-step-up': proof } }, Request),
    onSuccess: () => { setAsking(undefined); void refresh(); },
  });
  const withdraw = useMutation({
    mutationFn: (id: string) => http(`/api/v1/me/privacy-requests/${encodeURIComponent(id)}/withdraw`, { method: 'POST' }, Request),
    onSuccess: () => void refresh(),
  });
  const getLinks = async (id: string) => {
    const l = await http(`/api/v1/me/privacy-requests/${encodeURIComponent(id)}/download-link`, { method: 'POST' }, z.object({ url: z.string(), summaryUrl: z.string() }));
    setLinks(x => ({ ...x, [id]: l }));
  };
  const has = (type: string) => (requests.data ?? []).some(r => r.type === type && OPEN.includes(r.state));
  const line = (r: Request) => {
    const due = date(r.extendedTo ?? r.dueAt, 'long');
    if (r.type === 'erasure' && r.state === 'verified' && r.scheduledFor) return `${t('due', { reference: r.reference, date: due, law: r.law.shortName })} · ${t('scheduled', { date: date(r.scheduledFor, 'long') })}`;
    if (OPEN.includes(r.state)) return `${t('due', { reference: r.reference, date: due, law: r.law.shortName })} · ${t('preparing')}`;
    return t('closed', { reference: r.reference, state: r.export?.ready ? t('ready') : r.state });
  };
  return (
    <section className="nl-set-section" aria-labelledby="personal-data">
      <h2 id="personal-data">{t('title')}</h2>
      <p className="nl-small">{t('lede')}</p>
      <div className="nl-set-actions">
        <Button type="button" variant="secondary" disabled={has('access')} onClick={() => setAsking('access')}>{t('download')}</Button>
        <Button type="button" variant="ghost" disabled={has('erasure')} onClick={() => setAsking('erasure')}>{t('delete')}</Button>
      </div>
      {requests.isPending ? <Skeleton height={40} /> : requests.isError ? <ErrorState message={t('loadError')} onRetry={() => void requests.refetch()} /> : (
        <ul className="nl-set-list">
          {requests.data.map(r => (
            <li key={r.id}>
              <strong>{t(r.type)}</strong> · {line(r)}
              {r.type === 'erasure' && r.state === 'verified' ? <> <Button type="button" variant="ghost" onClick={() => withdraw.mutate(r.id)}>{t('cancel')}</Button></> : null}
              {r.type === 'access' && r.export?.ready ? (links[r.id]
                ? <> <a href={links[r.id]!.url} download>{t('getData')}</a> · <a href={links[r.id]!.summaryUrl} download>{t('getSummary')}</a></>
                : <> <Button type="button" variant="ghost" onClick={() => void getLinks(r.id)}>{t('getLinks')}</Button></>) : null}
            </li>
          ))}
        </ul>
      )}
      {asking ? (
        <Dialog open onClose={() => setAsking(undefined)} title={t('confirmTitle')} role={asking === 'erasure' ? 'alertdialog' : 'dialog'}>
          <StepUpPanel intro={<p>{asking === 'erasure' ? t('deleteIntro') : t('downloadIntro')}</p>} confirmLabel={t('confirm')} busy={open.isPending}
            onProof={proof => open.mutateAsync({ type: asking, proof })} onCancel={() => setAsking(undefined)} />
          {open.isError ? <p className="nl-error" role="alert">{open.error instanceof Error && open.error.message ? open.error.message : t('error')}</p> : null}
        </Dialog>
      ) : null}
    </section>
  );
}
