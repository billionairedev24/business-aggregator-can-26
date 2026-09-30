import { CaretDown, MapPin } from '@phosphor-icons/react';
import { defineMessages } from './i18n';
import { SiteLink } from './SiteLink';

/**
 * Where the consumer header thinks the person is (design 06, the pill after the brand):
 * locating (browser geolocation pending) · detected (device or IP) · fallback (nothing found: the default market) ·
 * saved (chosen on the Location screen) · denied (geolocation refused and nothing else known).
 */
export type LocationStatus = 'locating' | 'detected' | 'fallback' | 'saved' | 'denied';

const useT = defineMessages({
  en: {
    detected: 'Detected · deliver to', deliverTo: 'Deliver to', locating: 'Locating…', setLocation: 'Set location',
    titleDetected: 'Detected from your device · tap to change', titleDenied: 'Location access declined · set it manually', titleOther: 'Delivery location',
  },
  fr: {
    detected: 'Détecté · livrer à', deliverTo: 'Livrer à', locating: 'Localisation…', setLocation: 'Choisir un lieu',
    titleDetected: 'Détecté par votre appareil · touchez pour modifier', titleDenied: 'Accès refusé · choisissez manuellement', titleOther: 'Lieu de livraison',
  },
});

export interface LocationPillProps { status: LocationStatus; label?: string; href: string }

/** The location pill: map-pin, a kicker and the place; opens the Location screen. */
export function LocationPill({ status, label, href }: LocationPillProps) {
  const t = useT();
  const kicker = status === 'detected' ? t('detected') : t('deliverTo');
  const place = status === 'locating' ? t('locating') : status === 'denied' || !label ? t('setLocation') : label;
  const title = status === 'detected' ? t('titleDetected') : status === 'denied' ? t('titleDenied') : t('titleOther');
  return (
    <SiteLink href={href} className="nl-location" title={title} aria-label={`${kicker} ${place}, ${title}`} aria-busy={status === 'locating' || undefined}>
      <MapPin weight="duotone" size={22} className="nl-location-pin" aria-hidden />
      <span className="nl-location-text" aria-hidden>
        <span className="nl-location-kicker">{kicker}</span>
        <span className="nl-location-place">{place}</span>
      </span>
      <CaretDown size={13} className="nl-location-caret" aria-hidden />
    </SiteLink>
  );
}
