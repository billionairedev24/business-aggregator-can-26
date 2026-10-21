import { createFileRoute } from '@tanstack/react-router';
import { OffersScreen } from '../../features/offers/OffersScreen';

/** Offers (S-157): Northline's own offers by email or text to the consenting customers of a market. */
export const Route = createFileRoute('/_console/offers')({
  component: OffersScreen,
});
