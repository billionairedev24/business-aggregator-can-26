import { useLocalSearchParams } from 'expo-router';

import { BookingLink } from '../../../src/services/Booking';

/** `/bookings/<id>` — the booking's deep link (S-102 → S-100): the day-of screen, or the sign-off once the job is done. */
export default function Booking() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <BookingLink id={String(id)} />;
}
