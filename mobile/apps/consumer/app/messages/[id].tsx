import { useLocalSearchParams } from 'expo-router';

import { Thread } from '../../src/messages/Messages';

/** S-153: one conversation with a provider; the push's `/app/messages/<id>` lands here. */
export default function MessageThread() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <Thread id={String(id)} />;
}
