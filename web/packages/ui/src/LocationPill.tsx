import { MapPin, CaretDown } from '@phosphor-icons/react';
export type GeoStatus = 'locating' | 'detected' | 'saved' | 'denied';
export interface LocationPillProps { status: GeoStatus; label?: string; onClick?: () => void }
const kicker: Record<GeoStatus, string> = { locating: 'Finding you…', detected: 'Detected · deliver to', saved: 'Deliver to', denied: 'Location off' };
/** Auto-discovered delivery location. Pair with useGeolocation(); falls back to the saved address. */
export function LocationPill({ status, label, onClick }: LocationPillProps) {
  return (
    <button onClick={onClick} className="nl-location" aria-live="polite" style={{ display: 'flex', alignItems: 'center', gap: 8, border: 0, background: 'var(--color-surface)', boxShadow: 'inset 0 0 0 1px var(--color-divider)', borderRadius: 999, padding: '6px 14px 6px 10px', minHeight: 44, cursor: 'pointer', color: 'var(--color-text)' }}>
      <MapPin weight="duotone" size={22} color="var(--color-accent)" aria-hidden />
      <span style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', lineHeight: 1.15 }}>
        <span style={{ fontSize: 11, color: 'var(--color-neutral-700)' }}>{kicker[status]}</span>
        <span style={{ fontSize: 14, fontWeight: 600 }}>{status === 'denied' ? 'Set location' : status === 'locating' ? '—' : label}</span>
      </span>
      <CaretDown size={13} color="var(--color-neutral-700)" aria-hidden />
    </button>
  );
}
