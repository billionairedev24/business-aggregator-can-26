import { useNavigate, useSearch } from '@tanstack/react-router';
import { UnderlineTabs } from '@northline/ui';
import { SCREEN_PATH } from '../shell/screens';
import { FeedbackQueue, type UatSearch } from './FeedbackQueue';
import { GoNoGo } from './GoNoGo';
import { Participants } from './Participants';
import { useUatT } from './messages';
import './uat.css';

/** Pilot UAT (S-121): the feedback queue, participants and their sign-offs, the go/no-go report. */
export function UatScreen() {
  const t = useUatT();
  const search = useSearch({ strict: false }) as UatSearch;
  const navigate = useNavigate();
  const view = search.view ?? 'feedback';
  return (
    <div>
      <UnderlineTabs aria-label={t('tabs')} value={view}
        options={[{ value: 'feedback', label: t('tabFeedback') }, { value: 'participants', label: t('tabParticipants') }, { value: 'report', label: t('tabReport') }]}
        onChange={v => void navigate({ to: SCREEN_PATH.uat, search: v === 'feedback' ? {} : { view: v } })} />
      {view === 'participants' ? <Participants /> : view === 'report' ? <GoNoGo /> : <FeedbackQueue />}
    </div>
  );
}
