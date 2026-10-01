import { useFormatters } from '@northline/ui';
import type { CourierProgress } from './courier';
import { useCourierT } from './messages';
import './courier.css';

/**
 * Who is bringing the order and when (S-88): "Kai is on the way. 2 stops before yours. At your door about 7:10 pm.",
 * the live position's freshness, and the drop-off PIN until it's delivered. Nothing before a courier is planned.
 */
export function CourierStatus({ courier }: { courier: CourierProgress | null | undefined }) {
  const t = useCourierT();
  const { date } = useFormatters();
  if (!courier || courier.state === 'waiting' || courier.state === 'cancelled') return null;
  const name = courier.courierName;
  const moving = courier.state === 'picked_up';
  const lines: string[] = [];
  if (courier.state !== 'delivered') {
    lines.push(moving ? (name ? t('onTheWay', { name }) : t('onTheWayNoName')) : name ? t('assigned', { name }) : t('assignedNoName'));
    if (moving) lines.push(t('stops', { count: courier.stopsBefore }));
    if (courier.eta) lines.push(t('eta', { time: date(courier.eta, 'time') }));
  }
  return (
    <div className="nl-courier" aria-live="polite">
      {lines.length ? <p className="nl-courier-line">{lines.join(' ')}</p> : null}
      {moving && courier.positionAt ? <p className="nl-courier-live"><span aria-hidden className="nl-courier-dot" />{t('live', { time: date(courier.positionAt, 'time') })}</p> : null}
      {courier.pin ? <p className="nl-courier-pin"><strong>{t('pin', { pin: courier.pin })}</strong> · {t('pinHint')}</p> : null}
    </div>
  );
}
