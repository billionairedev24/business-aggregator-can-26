import { z } from 'zod';
import { http } from '@northline/client';

/**
 * Photos for a quote request or a booking's job details (S-153): uploaded first (`POST /api/v1/me/photos`, multipart
 * `file`), then their ids go with the request or the checkout (`photoIds`). JPEG, PNG, HEIC or WebP up to 10 MB, at
 * most 6 — the api checks the same (by type and file signature) and scans every file. Only the owner reads them here
 * (`GET /api/v1/me/photos/{id}`); the providers asked see them in their Studio.
 */
export const Photo = z.object({ id: z.string(), fileName: z.string(), contentType: z.string(), byteSize: z.number() });
export type Photo = z.infer<typeof Photo>;

export const PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/heic', 'image/heif', 'image/webp'] as const;
export const MAX_PHOTO_BYTES = 10 * 1024 * 1024;
export const MAX_PHOTOS = 6;

export const uploadJobPhoto = (file: File) => {
  const form = new FormData();
  form.append('file', file);
  return http('/api/v1/me/photos', { method: 'POST', body: form }, Photo);
};
export const photoUrl = (id: string) => `/api/v1/me/photos/${encodeURIComponent(id)}`;
