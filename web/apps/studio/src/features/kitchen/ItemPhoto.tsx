import { useState } from 'react';
import { photoUrl } from './api';

/** The dish's photo on the grey well, or the empty dashed well when there is none (or it can't be loaded). S-175: a size class, no inline style. */
export function ItemPhoto({ merchantId, itemId, hasPhoto, version, size = 'md' }: { merchantId: string; itemId?: string; hasPhoto: boolean; version?: string | null; size?: 'md' | 'lg' }) {
  const [broken, setBroken] = useState(false);
  if (!itemId || !hasPhoto || broken) return <div className={`nl-k-photo nl-k-photo-${size} nl-k-photo-empty`} aria-hidden />;
  return <img className={`nl-k-photo nl-k-photo-${size}`} src={photoUrl(merchantId, itemId, version)} alt="" loading="lazy" onError={() => setBroken(true)} />;
}
