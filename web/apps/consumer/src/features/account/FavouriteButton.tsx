import { useQuery } from '@tanstack/react-query';
import { Heart } from '@phosphor-icons/react';
import { useViewer } from '../session/api';
import { favouritesQuery, useFavourite } from './api';
import { useAccountT } from './messages';

/**
 * ♡ on a business's page (design 06 favourites: "tap ♡ on any provider or shop"). Signed-in people only — it renders
 * nothing until the browser knows who is signed in, so the server's HTML stays the same for everyone.
 */
export function FavouriteButton({ merchantId, className }: { merchantId: string; className?: string }) {
  const t = useAccountT();
  const { user } = useViewer();
  const list = useQuery({ ...favouritesQuery, enabled: !!user });
  const change = useFavourite();
  if (!user || !list.isSuccess) return null;
  const on = list.data.some(f => f.merchantId === merchantId);
  return (
    <button type="button" className={className ?? 'btn btn-ghost'} aria-pressed={on} aria-label={t(on ? 'unfavourite' : 'favourite')}
      disabled={change.isPending} onClick={() => change.mutate({ merchantId, on: !on })}>
      <Heart size={20} weight={on ? 'fill' : 'duotone'} aria-hidden />
    </button>
  );
}
