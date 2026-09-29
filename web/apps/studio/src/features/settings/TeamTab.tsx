import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, DataTable, Dialog, ErrorState, Field, PageSkeleton, Segmented, Select, Tag, TextInput, useFormatters, type DataTableColumn, type DataTableTone } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useMerchantId, useRole } from '../shell/api';
import { teamQuery, useChangeRole, useInvite, useRemoveMember, useWithdrawInvitation, type InvitationCreated, type Member, type Role } from './api';
import { useSettingsT, type SettingsT } from './messages';
import { inviteErrors, localizeServerErrors, type InviteForm } from './validation';

interface MemberRow { id: string; name: string; note: string; role: string; can: string; fa: string; faTone: DataTableTone; member: Member }

const faOf = (m: Member, t: SettingsT): [string, DataTableTone] => {
  switch (m.secondFactor) {
    case 'passkey': return [t('fa_passkey'), 'tag-accent'];
    case 'totp': return [t('fa_totp'), 'tag-accent'];
    case 'sms': return [t('fa_sms'), 'tag-accent-2'];
    default: return [t('fa_none'), 'tag-accent-2'];
  }
};

const failure = (e: unknown, t: SettingsT) => (e instanceof ApiError && e.status === 409 && (e.body as { code?: string } | undefined)?.code === 'last_owner' ? t('lastOwner') : t('actionFailed'));

/** Team & roles (design dt.team): DataTable with owner-only CRUD, invitations by email or mobile. */
export function TeamTab() {
  const t = useSettingsT();
  const merchantId = useMerchantId();
  const role = useRole();
  const owner = role === 'owner';
  const q = useQuery(teamQuery(merchantId));
  const change = useChangeRole(merchantId);
  const remove = useRemoveMember(merchantId);
  const withdraw = useWithdrawInvitation(merchantId);
  const f = useFormatters();
  const [inviting, setInviting] = useState(false);
  if (q.isPending) return <PageSkeleton kpis={0} rows={4} />;
  if (q.isError) return <ErrorState message={t('loadError')} onRetry={() => void q.refetch()} />;

  const roleLabel = (r: Role) => t(`role_${r}`);
  const byLabel = new Map(q.data.roles.map(r => [roleLabel(r), r]));
  const rows: MemberRow[] = q.data.members.map(m => {
    const [fa, faTone] = faOf(m, t);
    return { id: m.userId, name: m.name, note: m.you ? t('you') : '', role: roleLabel(m.role), can: t(`can_${m.role}`), fa, faTone, member: m };
  });
  const columns: DataTableColumn<MemberRow>[] = [
    { key: 'name', label: t('colMember'), sub: 'note', primary: true, editable: false },
    { key: 'role', label: t('colRole'), options: q.data.roles.map(roleLabel), required: true, filter: 'facet' },
    { key: 'can', label: t('colCan'), editable: false },
    { key: 'fa', label: t('col2fa'), type: 'tag', editable: false },
  ];

  return (
    <div className="nl-set-team">
      <div className="nl-set-table">
        <DataTable<MemberRow>
          entity={t('teamEntity')} plural={t('teamPlural')} columns={columns} rows={rows} rowTones={r => ({ fa: r.faTone })}
          roleName={roleLabel(role as Role)} can={{ create: false, update: owner, delete: owner, export: true }}
          onUpdate={async (row, values) => {
            const next = byLabel.get(String(values.role ?? row.role));
            if (!next || next === row.member.role) return;
            try { await change.mutateAsync({ userId: row.id, role: next }); } catch (e) { throw new Error(failure(e, t)); }
          }}
          onDelete={async list => {
            try { for (const r of list) await remove.mutateAsync(r.id); } catch (e) { throw new Error(failure(e, t)); }
          }}
        />
      </div>
      {q.data.invitations.length > 0 && (
        <section aria-labelledby="set-inv" className="nl-set-invitations">
          <h3 id="set-inv" className="nl-set-h3">{t('pendingTitle')}</h3>
          <ul className="nl-set-rows">
            {q.data.invitations.map(i => {
              const contact = i.email ?? i.phone ?? '';
              return (
                <li key={i.id} className="nl-set-row">
                  <span><strong>{contact}</strong> · {roleLabel(i.role)}</span>
                  <span className="nl-set-rowside">
                    <Tag tone={i.state === 'pending' ? 'neutral' : 'accent-2'}>{i.state === 'pending' ? t('pendingState', { date: f.date(i.expiresAt) }) : t('expiredState')}</Tag>
                    {owner && <Button variant="ghost" aria-label={t('withdrawLabel', { contact })} disabled={withdraw.isPending} onClick={() => withdraw.mutate(i.id)}>{t('withdraw')}</Button>}
                  </span>
                </li>
              );
            })}
          </ul>
        </section>
      )}
      {owner && <Button variant="secondary" className="nl-set-invite" onClick={() => setInviting(true)}>{t('invite')}</Button>}
      {inviting && <InviteDialog roles={[...q.data.roles.filter(r => r !== 'owner'), 'owner' as const]} onClose={() => setInviting(false)} />}
    </div>
  );
}

function InviteDialog({ roles, onClose }: { roles: Role[]; onClose: () => void }) {
  const t = useSettingsT();
  const merchantId = useMerchantId();
  const invite = useInvite(merchantId);
  const [v, setV] = useState<InviteForm>({ by: 'email', email: '', phone: '', role: roles[0] ?? '' });
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [submitted, setSubmitted] = useState(false);
  const [server, setServer] = useState<Record<string, string>>({});
  const [created, setCreated] = useState<InvitationCreated | null>(null);
  const [copied, setCopied] = useState(false);
  const errors = inviteErrors(v, t);
  const shown = (k: string) => server[k] ?? ((touched[k] || submitted) ? errors[k] : undefined);
  const count = Object.values(errors).filter(Boolean).length;
  const set = (patch: Partial<InviteForm>) => { setV(x => ({ ...x, ...patch })); setServer({}); };

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
    if (count) return;
    invite.mutate(v.by === 'email' ? { email: v.email.trim(), role: v.role } : { phone: v.phone.trim(), role: v.role }, {
      onSuccess: setCreated,
      onError: err => { if (err instanceof ValidationError) setServer(localizeServerErrors(err.errors, t)); },
    });
  };

  if (created) {
    const contact = created.invitation.email ?? created.invitation.phone ?? '';
    return (
      <Dialog open onClose={onClose} title={t('inviteTitle')} actions={<Button onClick={onClose}>{t('done')}</Button>}>
        <p>{created.sent ? t('inviteSent', { contact }) : t('inviteNotSent', { contact })}</p>
        <div className="nl-set-secret">
          <code>{created.inviteUrl}</code>
          <Button variant="secondary" onClick={() => { void navigator.clipboard?.writeText(created.inviteUrl); setCopied(true); }}>{copied ? t('copied') : t('copyLink')}</Button>
        </div>
      </Dialog>
    );
  }
  return (
    <Dialog open onClose={onClose} title={t('inviteTitle')}>
      <form onSubmit={submit} noValidate className="nl-set-dialogform">
        {submitted && (count > 0 || Object.keys(server).length > 0) && <Alert tone="error" role="alert">{t('attention', { n: count || Object.keys(server).length })}</Alert>}
        <Field label={t('inviteBy')}>
          <Segmented name="invite-by" aria-label={t('inviteBy')} value={v.by} onChange={by => set({ by })} options={[{ value: 'email', label: t('byEmail') }, { value: 'phone', label: t('byPhone') }]} />
        </Field>
        {v.by === 'email' ? (
          <Field label={t('email')} error={shown('email')}>
            <TextInput type="email" autoComplete="off" value={v.email} placeholder={t('emailPh')} onChange={e => set({ email: e.target.value })} onBlur={() => setTouched(x => ({ ...x, email: true }))} />
          </Field>
        ) : (
          <Field label={t('phone')} error={shown('phone')}>
            <TextInput type="tel" autoComplete="off" value={v.phone} placeholder={t('phonePh')} onChange={e => set({ phone: e.target.value })} onBlur={() => setTouched(x => ({ ...x, phone: true }))} />
          </Field>
        )}
        <Field label={t('role')} error={shown('role')}>
          <Select value={v.role} onChange={e => set({ role: e.target.value })} options={roles.map(r => ({ value: r, label: t(`role_${r}`) }))} />
        </Field>
        <p className="nl-small nl-muted">{t('inviteNote')}</p>
        {invite.isError && !(invite.error instanceof ValidationError) && <p role="alert" className="nl-error">{t('actionFailed')}</p>}
        <div className="nl-set-dialogactions">
          <Button type="button" variant="ghost" onClick={onClose}>{t('cancel')}</Button>
          <Button type="submit" disabled={invite.isPending}>{invite.isPending ? t('sending') : t('sendInvite')}</Button>
        </div>
      </form>
    </Dialog>
  );
}
