import { useRef, useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useRouterState } from '@tanstack/react-router';
import { Sparkle } from '@phosphor-icons/react';
import { Alert, Button, ChatBubble, ChatLog, Drawer, TextArea } from '@northline/ui';
import { screenFromPath, screenHref, type ScreenKey } from '../shell/nav';
import { aiErrorKind, aiStatusQuery, streamChat, useConfirmAction, type PendingAction, type ToolRun, type Turn } from './api';
import { useAssistantT } from './messages';
import './Assistant.css';

interface Entry extends Turn { tools?: ToolRun[]; pending?: PendingAction | null; screen?: string | null; note?: string; failed?: boolean }

const SCREENS: readonly string[] = ['dashboard', 'orders', 'appointments', 'products', 'availability', 'earnings', 'payouts', 'messages', 'reviews'];

/** Top-bar button + right drawer (S-130). Hidden when no model is configured here (GET /api/v1/ai/status). */
export function AssistantButton({ merchantId }: { merchantId: string }) {
  const t = useAssistantT();
  const status = useQuery(aiStatusQuery);
  const [open, setOpen] = useState(false);
  if (!status.data?.available) return null;
  return <>
    <button type="button" className="btn btn-ghost nl-assistant-open" onClick={() => setOpen(true)} aria-haspopup="dialog">
      <Sparkle size={18} weight="duotone" aria-hidden />{t('open')}
    </button>
    <AssistantDrawer merchantId={merchantId} open={open} onClose={() => setOpen(false)} fake={status.data.provider === 'fake'} />
  </>;
}

export function AssistantDrawer({ merchantId, open, onClose, fake }: { merchantId: string; open: boolean; onClose: () => void; fake?: boolean }) {
  const t = useAssistantT();
  const nav = useNavigate();
  const qc = useQueryClient();
  const pathname = useRouterState({ select: s => s.location.pathname });
  const [entries, setEntries] = useState<Entry[]>([]);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const abort = useRef<AbortController | null>(null);
  const confirm = useConfirmAction(merchantId);

  const update = (fn: (last: Entry) => Entry) => setEntries(es => es.length ? [...es.slice(0, -1), fn(es[es.length - 1]!)] : es);

  async function ask(question: string) {
    const q = question.trim();
    if (!q || busy) return;
    const history: Turn[] = [...entries.filter(e => !e.failed && !e.note).map(({ role, content }) => ({ role, content })), { role: 'user', content: q }];
    setEntries(es => [...es, { role: 'user', content: q }, { role: 'assistant', content: '', tools: [] }]);
    setDraft('');
    setBusy(true);
    abort.current = new AbortController();
    try {
      const answer = await streamChat(merchantId, { messages: history.slice(-20), screen: screenFromPath(pathname) }, {
        tool: run => update(e => ({ ...e, tools: [...(e.tools ?? []), run] })),
        delta: text => update(e => ({ ...e, content: e.content + text })),
      }, abort.current.signal);
      update(e => ({ ...e, content: answer.content, tools: answer.toolRuns, pending: answer.pending, screen: answer.screen }));
    } catch (err) {
      if ((err as Error).name === 'AbortError') { update(e => ({ ...e, failed: !e.content })); return; }
      const kind = aiErrorKind(err);
      update(e => ({ ...e, failed: true, content: t(kind === 'rate' ? 'rate' : kind === 'off' ? 'off' : 'error') }));
    } finally {
      setBusy(false);
      abort.current = null;
    }
  }

  async function decide(index: number, action: PendingAction, ok: boolean) {
    setEntries(es => es.map((e, i) => (i === index ? { ...e, pending: null } : e)));
    if (!ok) { setEntries(es => [...es, { role: 'assistant', content: t('cancelled'), note: 'cancelled' }]); return; }
    try {
      const done = await confirm.mutateAsync(action);
      setEntries(es => [...es, { role: 'assistant', content: t('done', { summary: done.summary }), note: 'done', screen: done.screen }]);
      void qc.invalidateQueries({ queryKey: ['merchant', merchantId] });
    } catch (err) {
      const kind = aiErrorKind(err);
      setEntries(es => [...es, { role: 'assistant', content: kind === 'other' && err instanceof Error ? err.message : t(kind === 'rate' ? 'rate' : 'off'), failed: true }]);
    }
  }

  const submit = (e: FormEvent) => { e.preventDefault(); void ask(draft); };
  const screenLabel = (s: string) => (SCREENS.includes(s) ? t(`screen_${s}` as Parameters<typeof t>[0]) : s);

  return (
    <Drawer open={open} onClose={onClose} title={t('title')} width={440}
      footer={<form className="nl-assistant-form" onSubmit={submit}>
        <label className="nl-sr-only" htmlFor="nl-assistant-q">{t('label')}</label>
        <TextArea id="nl-assistant-q" rows={2} maxLength={4000} value={draft} placeholder={t('placeholder')} disabled={busy}
          onChange={e => setDraft(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void ask(draft); } }} />
        <div className="nl-assistant-form-row">
          {entries.length ? <Button type="button" variant="ghost" onClick={() => setEntries([])} disabled={busy}>{t('clear')}</Button> : <span />}
          {busy ? <Button type="button" variant="secondary" onClick={() => abort.current?.abort()}>{t('stop')}</Button>
            : <Button type="submit" disabled={!draft.trim()}>{t('send')}</Button>}
        </div>
        <p className="nl-assistant-disclaimer">{t('disclaimer')}</p>
      </form>}>
      {fake ? <Alert tone="neutral">{t('fake')}</Alert> : null}
      {entries.length === 0 ? <div className="nl-assistant-empty">
        <p>{t('intro')}</p>
        <p className="nl-assistant-try">{t('suggestions')}</p>
        <div className="nl-assistant-chips">
          {(['s_today', 's_pack', 's_payout', 's_reviews'] as const).map(k => <button key={k} type="button" className="nl-chip" onClick={() => void ask(t(k))}>{t(k)}</button>)}
        </div>
      </div> : null}
      <ChatLog label={t('log')} className="nl-assistant-log">
        {entries.map((e, i) => e.role === 'user'
          ? <ChatBubble key={i} side="me" sender={t('you')}>{e.content}</ChatBubble>
          : <ChatBubble key={i} side="them" sender={t('assistant')} failed={e.failed}
              pending={busy && i === entries.length - 1 && !e.content}
              meta={e.tools?.length ? t('tools', { list: e.tools.map(r => r.summary).join(' · ') }) : undefined}
              footer={<>
                {e.pending ? <div className="nl-assistant-confirm" role="group" aria-label={t('confirmTitle')}>
                  <strong>{t('confirmTitle')}</strong>
                  <span>{e.pending.preview}</span>
                  <div className="nl-assistant-confirm-actions">
                    <Button type="button" onClick={() => void decide(i, e.pending!, true)} disabled={confirm.isPending}>{t('confirm')}</Button>
                    <Button type="button" variant="secondary" onClick={() => void decide(i, e.pending!, false)}>{t('cancel')}</Button>
                  </div>
                </div> : null}
                {e.screen && SCREENS.includes(e.screen) && !e.failed
                  ? <button type="button" className="nl-assistant-link" onClick={() => { onClose(); void nav({ to: screenHref(merchantId, e.screen as ScreenKey) }); }}>{t('openScreen', { screen: screenLabel(e.screen) })} →</button>
                  : null}
              </>}>
              {e.content || (busy && i === entries.length - 1 ? t('thinking') : '')}
            </ChatBubble>)}
      </ChatLog>
    </Drawer>
  );
}
