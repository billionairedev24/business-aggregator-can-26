import { useEffect, useRef, useState } from 'react';
import { Switch } from '@northline/ui';
import { http } from '../../lib/http';
import { useAppointmentsT } from './messages';

/** How often a position goes out while sharing (the api keeps the latest for 5 minutes and accepts one per 5 s). */
export const SHARE_EVERY_MS = 15_000;

/**
 * "On my way" with location sharing (mobile gaps part 2): while the job is en route, the member doing it may share
 * their position from the phone's browser — off until they switch it on (their consent, and the browser's permission
 * prompt). The latest position goes to the api every 15 s and is kept 5 minutes in Valkey, never stored; the customer
 * sees minutes away, not where the member is. Switching off, arriving or leaving the screen stops it.
 */
export function LiveShare({ merchantId, jobId }: { merchantId: string; jobId: string }) {
  const t = useAppointmentsT();
  const [on, setOn] = useState(false);
  const [status, setStatus] = useState<'idle' | 'sharing' | 'denied' | 'unavailable'>('idle');
  const last = useRef(0);
  const path = `/api/v1/merchants/${encodeURIComponent(merchantId)}/jobs/${encodeURIComponent(jobId)}/position`;

  useEffect(() => {
    if (!on) return;
    if (typeof navigator === 'undefined' || !navigator.geolocation) { setStatus('unavailable'); setOn(false); return; }
    const send = (p: GeolocationPosition) => {
      const now = Date.now();
      if (now - last.current < SHARE_EVERY_MS) return;
      last.current = now;
      setStatus('sharing');
      void http(path, { method: 'POST', body: { lat: p.coords.latitude, lng: p.coords.longitude } }).catch(() => undefined);
    };
    const watch = navigator.geolocation.watchPosition(send, e => { setStatus(e.code === e.PERMISSION_DENIED ? 'denied' : 'unavailable'); setOn(false); }, { enableHighAccuracy: true, maximumAge: 10_000 });
    return () => {
      navigator.geolocation.clearWatch(watch);
      last.current = 0;
      void http(path, { method: 'DELETE' }).catch(() => undefined);
    };
  }, [on, path]);

  return (
    <div className="nl-appt-share">
      <Switch checked={on} onChange={setOn} label={t('shareLabel')} />
      <div className="nl-hint" aria-live="polite">
        {status === 'denied' ? t('shareDenied') : status === 'unavailable' ? t('shareUnavailable') : on && status === 'sharing' ? t('shareOn') : t('shareHint')}
      </div>
    </div>
  );
}
