import { CaretDown, MapPin } from '@phosphor-icons/react';
import { defineMessages } from './i18n';
import { SiteLink } from './SiteLink';

/**
 * Where the consumer header thinks the person is, and how it knows (consumer location, 2026-10-19):
 * saved (chosen in the location dialog) · address (the signed-in person's default address) · detected (the device,
 * when they asked for it, or the CDN's city) · fallback (the configured fallback market) · locating (before the browser
 * has read its sources) · denied (nothing known: asks to set a place).
 */
export type LocationStatus = 'locating' | 'detected' | 'address' | 'fallback' | 'saved' | 'denied';

const useT = defineMessages({
  en: {
    deliverTo: 'Deliver to', near: 'Near', locating: 'Finding your area…', setLocation: 'Set your location',
    change: '{kicker} {place}. Change location',
  },
  fr: {
    deliverTo: 'Livrer à', near: 'Près de', locating: 'Recherche de votre secteur…', setLocation: 'Indiquez votre adresse',
    change: '{kicker} {place}. Changer d’adresse',
  },
});

export interface LocationPillProps {
  status: LocationStatus;
  label?: string;
  /** "Deliver to" (default: shopping) or "Near" (services and providers). */
  kicker?: 'deliverTo' | 'near';
  /** Opens the location dialog. */
  onOpen?: () => void;
  /** Without script (or without `onOpen`): a link to the location page. */
  href?: string;
}

/**
 * The header's location control: a pin, a small "Deliver to" / "Near" over the place in bold — the place is shown at
 * every width, ellipsed when long, never the pin alone. A button that opens the location dialog (`onOpen`), else a
 * link (`href`).
 */
export function LocationPill({ status, label, kicker = 'deliverTo', onOpen, href = '/location' }: LocationPillProps) {
  const t = useT();
  const top = t(kicker);
  const place = status === 'locating' ? t('locating') : status === 'denied' || !label ? t('setLocation') : label;
  const body = (
    <>
      <MapPin size={20} weight="fill" className="nl-location-pin" aria-hidden />
      <span className="nl-location-text" aria-hidden>
        <span className="nl-location-kicker">{top}</span>
        <span className="nl-location-place">{place}</span>
      </span>
      <CaretDown size={14} className="nl-location-caret" aria-hidden />
    </>
  );
  const name = t('change', { kicker: top, place });
  return onOpen
    ? <button type="button" className="nl-location" aria-label={name} aria-haspopup="dialog" aria-busy={status === 'locating' || undefined} onClick={onOpen}>{body}</button>
    : <SiteLink href={href} className="nl-location" aria-label={name} aria-busy={status === 'locating' || undefined}>{body}</SiteLink>;
}
