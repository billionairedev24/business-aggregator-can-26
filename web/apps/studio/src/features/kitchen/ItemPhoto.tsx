import { useState } from 'react';
import { photoUrl } from './api';

/** The item's photo, or the design's halftone placeholder when there is none (or it can't be loaded). */
export function ItemPhoto({ merchantId, itemId, hasPhoto, version, size = 56 }: { merchantId: string; itemId?: string; hasPhoto: boolean; version?: string | null; size?: number }) {
  const [broken, setBroken] = useState(false);
  const style = { width: size, height: size };
  if (!itemId || !hasPhoto || broken) return <div className="halftone nl-k-photo" style={style} aria-hidden />;
  return <img className="nl-k-photo" style={style} src={photoUrl(merchantId, itemId, version)} alt="" loading="lazy" onError={() => setBroken(true)} />;
}
