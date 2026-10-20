import { useState } from 'react';
import { ValidationError } from '@northline/client';
import { FileButton } from '@northline/ui';
import { MAX_PHOTO_BYTES, MAX_PHOTOS, PHOTO_TYPES, photoUrl, uploadJobPhoto, type Photo } from './api';
import { usePhotosT } from './messages';
import './photos.css';

/**
 * "Photos (optional)" for a quote request or a booking's job details (S-153): each photo is uploaded as it is picked
 * (checked first in the browser with the api's own rules and words), shown as a thumbnail, and can be removed before
 * sending. The parent keeps the list and sends the ids.
 */
export function PhotoPicker({ photos, onChange, disabled }: { photos: Photo[]; onChange: (photos: Photo[]) => void; disabled?: boolean }) {
  const t = usePhotosT();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const add = async (file: File) => {
    if (photos.length >= MAX_PHOTOS) { setError(t('count')); return; }
    if (!(PHOTO_TYPES as readonly string[]).includes(file.type)) { setError(t('type')); return; }
    if (file.size > MAX_PHOTO_BYTES) { setError(t('size')); return; }
    setBusy(true); setError(undefined);
    try { onChange([...photos, await uploadJobPhoto(file)]); } catch (e) {
      setError(e instanceof ValidationError ? (e.byField().file ?? e.errors[0]?.message) : t('failed'));
    } finally { setBusy(false); }
  };
  return (
    <div className="nl-photos" role="group" aria-labelledby="nl-photos-title">
      <span id="nl-photos-title" className="nl-photos-title">{t('title')}</span>
      <p className="nl-photos-hint">{t('hint')}</p>
      {photos.length > 0 ? (
        <ul className="nl-photos-list">
          {photos.map((p, i) => (
            <li key={p.id} className="nl-photos-item">
              <img src={photoUrl(p.id)} alt={t('photoAlt', { n: i + 1 })} width={88} height={88} />
              <button type="button" className="btn btn-ghost btn-sm" disabled={disabled} onClick={() => onChange(photos.filter(x => x.id !== p.id))}>{t('remove', { name: p.fileName })}</button>
            </li>
          ))}
        </ul>
      ) : null}
      {photos.length < MAX_PHOTOS ? (
        <FileButton accept={PHOTO_TYPES.join(',')} onFile={f => void add(f)} disabled={disabled || busy} pending={busy}>{busy ? t('adding') : t('add')}</FileButton>
      ) : null}
      {error ? <p className="nl-error" role="alert">{error}</p> : null}
    </div>
  );
}

/** The photos of a request or a booking, read-only (the owner's own). */
export function PhotoStrip({ ids }: { ids: string[] }) {
  const t = usePhotosT();
  if (ids.length === 0) return null;
  return (
    <ul className="nl-photos-list" aria-label={t('yours')}>
      {ids.map((id, i) => <li key={id} className="nl-photos-item"><img src={photoUrl(id)} alt={t('photoAlt', { n: i + 1 })} width={88} height={88} /></li>)}
    </ul>
  );
}
