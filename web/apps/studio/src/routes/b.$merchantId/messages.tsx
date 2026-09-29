import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';
import { MessagesScreen } from '../../features/messages/MessagesScreen';

/** `?thread=<id>` opens a conversation (deep links from notifications, bookings and orders). */
export const Route = createFileRoute('/b/$merchantId/messages')({
  validateSearch: z.object({ thread: z.string().optional() }),
  component: function MessagesRoute() {
    const { thread } = Route.useSearch();
    const navigate = Route.useNavigate();
    return <MessagesScreen threadId={thread} onSelect={id => void navigate({ search: { thread: id }, replace: true })} />;
  },
});
