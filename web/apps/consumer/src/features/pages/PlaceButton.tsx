import type { ReactNode } from 'react';
import { useDeliveryLocation } from '../location/useDeliveryLocation';

/** "Change location" in place: opens the location dialog over the page (not the /location redirect to home). */
export function PlaceButton({ className = 'btn btn-primary', children }: { className?: string; children: ReactNode }) {
  const { chooser } = useDeliveryLocation();
  return <button type="button" className={className} onClick={chooser.show}>{children}</button>;
}
