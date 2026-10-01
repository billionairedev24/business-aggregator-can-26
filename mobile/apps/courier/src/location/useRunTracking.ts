import { useCallback, useEffect, useState } from 'react';
import { AppState } from 'react-native';

import { useI18n } from '../i18n';
import { locationAccess, requestLocationAccess, startTracking, stopTracking, type LocationAccess } from './tracker';

/**
 * Location sharing follows the run: on while a run is open (and the courier allowed it), off otherwise.
 * Returns the current access and `allow()` for the location card's button.
 */
export function useRunTracking(runOpen: boolean) {
  const { t } = useI18n();
  const [access, setAccess] = useState<LocationAccess>('undetermined');

  const sync = useCallback(async (): Promise<LocationAccess> => {
    if (!runOpen) {
      await stopTracking();
      return locationAccess();
    }
    return startTracking({ notificationTitle: t('location.notificationTitle'), notificationBody: t('location.notificationBody') });
  }, [runOpen, t]);

  useEffect(() => {
    let alive = true;
    const update = () =>
      void sync().then((a) => {
        if (alive) setAccess(a);
      });
    update();
    // back from Settings: the courier may have changed the permission
    const sub = AppState.addEventListener('change', (s) => {
      if (s === 'active') update();
    });
    return () => {
      alive = false;
      sub.remove();
    };
  }, [sync]);

  const allow = useCallback(async () => {
    const asked = await requestLocationAccess();
    setAccess(asked === 'denied' ? asked : await sync());
  }, [sync]);

  return { access, allow };
}
