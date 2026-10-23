import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { BoHeader, Button, DataTable, Dialog, ErrorState, Field, formatNumber, PageSkeleton, Select, Tag, TextInput, UnderlineTabs, useLocale, type DataTableColumn } from '@northline/ui';
import { ApiError, ValidationError } from '../../lib/http';
import { useGrant } from '../shell/grant';
import { useShellT, type ShellKey } from '../shell/messages';
import { AuditLog } from './AuditLog';
import { ROLES, teamQuery, useGrantRole, useInvite, useRevokeRole, type Member, type Team } from './api';
import { useTeamT } from './messages';
import './team.css';

const SCREEN_LABEL: Record<string, ShellKey> = { oncall: 'oncallScreen', profile: 'profileScreen' };
const errorOf = (e: unknown) => (e instanceof ValidationError ? Object.values(e.byField())[0] : e instanceof ApiError ? e.message : undefined);

/**
 * Team, roles & audit (S-96, design 03 `team`; admin, trust & safety, finance open it): the console roles with who holds
 * them, granting and removing roles (admins only — replaces the runbook's SQL; audited), and the audit log with filters.
 */
export function TeamScreen() {
  const t = useTeamT();
  const query = useQuery(teamQuery);
  if (query.isPending) return <div className="bo-page"><BoHeader title={t('heading')} /><PageSkeleton kpis={0} rows={6} /></div>;
  if (query.isError) return <div className="bo-page"><BoHeader title={t('heading')} /><ErrorState message={t('loadError')} onRetry={() => void query.refetch()} /></div>;
  return <TeamView team={query.data} />;
}

interface RoleRowView { id: string; role: string; people: number; can: string; needs: string }
interface PersonRow { id: string; name: string; email: string; roles: string; src: Member }

function TeamView({ team }: { team: Team }) {
  const t = useTeamT();
  const shell = useShellT();
  const { can, roleName } = useGrant();
  const { locale } = useLocale();
  const admin = can('province');
  const [tab, setTab] = useState<'people' | 'roles' | 'audit'>('people');
  const [inviting, setInviting] = useState(false);
  const [managing, setManaging] = useState<Member | null>(null);
  const screenName = (s: string) => shell(SCREEN_LABEL[s] ?? (s as ShellKey));
  const roleRows: RoleRowView[] = team.roles.map(r => ({
    id: r.role, role: shell(`role_${r.role}` as ShellKey), people: r.people,
    can: r.role === 'admin' ? t('allScreens') : r.screens.map(screenName).join(', '), needs: t('needs'),
  }));
  const roleColumns: DataTableColumn<RoleRowView>[] = [
    { key: 'role', label: t('c_role'), primary: true }, { key: 'people', label: t('c_people'), type: 'num' }, { key: 'can', label: t('c_can') }, { key: 'needs', label: t('c_needs') },
  ];
  const people: PersonRow[] = team.members.map(m => ({
    id: m.id, name: m.name, email: m.email ?? '—', src: m,
    roles: m.roles.filter(r => r !== 'staff').map(r => shell(`role_${r}` as ShellKey)).join(', ') || '—',
  }));
  const peopleColumns: DataTableColumn<PersonRow>[] = [
    { key: 'name', label: t('c_name'), primary: true }, { key: 'email', label: t('c_email') }, { key: 'roles', label: t('c_roles') },
  ];
  return (
    <div className="bo-page">
      <BoHeader title={t('heading')} sub={t('summary')}
        actions={<><Button disabled={!admin} onClick={() => setInviting(true)}>{t('invite')}</Button>{!admin ? <span className="bo-muted">{t('cannot')}</span> : null}</>} />
      {/* S-177: one tab row with counts, the dense table under it */}
      <UnderlineTabs aria-label={t('heading')} value={tab} onChange={setTab} options={[
        { value: 'people', label: t('peopleTitle'), count: formatNumber(people.length, locale) },
        { value: 'roles', label: t('rolesTitle'), count: formatNumber(roleRows.length, locale) },
        { value: 'audit', label: t('auditTitle') },
      ]} />
      {tab === 'people' ? (
        <DataTable<PersonRow> dense entity={t('personEntity')} plural={t('personPlural')} columns={peopleColumns} rows={people} pageSize={25} roleName={roleName}
          can={{ create: false, update: false, delete: false }} openLabel={t('manage')} onOpen={admin ? r => setManaging(r.src) : undefined} />
      ) : tab === 'roles' ? (
        <DataTable<RoleRowView> dense entity={t('roleEntity')} plural={t('rolePlural')} columns={roleColumns} rows={roleRows} pageSize={25} roleName={roleName}
          can={{ create: false, update: false, delete: false }} />
      ) : <AuditLog staff={team.members} />}
      {inviting ? <InviteDialog onClose={() => setInviting(false)} /> : null}
      {managing ? <ManageDialog member={team.members.find(m => m.id === managing.id) ?? managing} onClose={() => setManaging(null)} /> : null}
    </div>
  );
}

function InviteDialog({ onClose }: { onClose: () => void }) {
  const t = useTeamT();
  const shell = useShellT();
  const invite = useInvite();
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<string>('support');
  const errors = invite.error instanceof ValidationError ? invite.error.byField() : {};
  return (
    <Dialog open onClose={onClose} title={t('inviteTitle')}
      actions={<><Button variant="tertiary" onClick={onClose}>{t('cancel')}</Button><Button disabled={invite.isPending} onClick={() => invite.mutate({ email, role }, { onSuccess: onClose })}>{t('grant')}</Button></>}>
      <p className="nl-tm-sub">{t('inviteNote')}</p>
      <Field label={t('f_email')} error={errors.email}><TextInput type="email" value={email} onChange={e => setEmail(e.target.value)} /></Field>
      <Field label={t('f_role')} error={errors.role}>
        <Select value={role} options={ROLES.map(r => ({ value: r, label: shell(`role_${r}` as ShellKey) }))} onChange={e => setRole(e.target.value)} />
      </Field>
      {invite.error && !(invite.error instanceof ValidationError) ? <p role="alert" className="nl-tm-error">{errorOf(invite.error)}</p> : null}
    </Dialog>
  );
}

function ManageDialog({ member, onClose }: { member: Member; onClose: () => void }) {
  const t = useTeamT();
  const shell = useShellT();
  const grant = useGrantRole();
  const revoke = useRevokeRole();
  const held = member.roles.filter(r => r !== 'staff');
  const missing = ROLES.filter(r => !held.includes(r));
  const [role, setRole] = useState<string>(missing[0] ?? '');
  const error = errorOf(grant.error) ?? errorOf(revoke.error);
  return (
    <Dialog open onClose={onClose} title={t('manageTitle', { name: member.name })} actions={<Button variant="tertiary" onClick={onClose}>{t('close')}</Button>}>
      <div className="nl-tm-tags">
        {held.map(r => (
          <Tag key={r} tone="info">{shell(`role_${r}` as ShellKey)}
            <button type="button" className="nl-tm-x" aria-label={t('remove', { role: shell(`role_${r}` as ShellKey) })} disabled={revoke.isPending}
              onClick={() => revoke.mutate({ userId: member.id, role: r })}>×</button>
          </Tag>
        ))}
      </div>
      {missing.length ? (
        <div className="nl-tm-actions">
          <Select aria-label={t('addRole')} value={role} options={missing.map(r => ({ value: r, label: shell(`role_${r}` as ShellKey) }))} onChange={e => setRole(e.target.value)} />
          <Button variant="secondary" disabled={grant.isPending || !role} onClick={() => grant.mutate({ userId: member.id, role })}>{t('grant')}</Button>
        </div>
      ) : null}
      {error ? <p role="alert" className="nl-tm-error">{error}</p> : null}
    </Dialog>
  );
}
