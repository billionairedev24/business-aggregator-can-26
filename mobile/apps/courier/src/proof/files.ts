import { Directory, File, Paths } from 'expo-file-system';
import { Platform } from 'react-native';

import { base64 } from '@northline/mobile-kit';

import type { ProofFile } from '../api/courier';

/**
 * Proof files waiting in the outbox live in the app's document directory (the OS may purge the cache before the
 * phone is back online) and are deleted once the api has them or the action is dropped.
 */
function proofDir(): Directory {
  const dir = new Directory(Paths.document, 'proofs');
  if (!dir.exists) dir.create({ intermediates: true, idempotent: true });
  return dir;
}

/** Moves a camera photo (cache) into the outbox's directory. */
export async function keepPhoto(uri: string, id: string): Promise<ProofFile> {
  const name = `${id}.jpg`;
  if (Platform.OS === 'web') return { uri, type: 'image/jpeg', name };
  const target = new File(proofDir(), name);
  await new File(uri).move(target);
  return { uri: target.uri, type: 'image/jpeg', name };
}

/** Writes the signature's PNG bytes for the upload. */
export function keepSignature(png: Uint8Array, id: string): ProofFile {
  const name = `${id}.png`;
  if (Platform.OS === 'web') return { uri: `data:image/png;base64,${base64(png)}`, type: 'image/png', name };
  const file = new File(proofDir(), name);
  file.write(png);
  return { uri: file.uri, type: 'image/png', name };
}

export function releaseProofFile(file: ProofFile | undefined) {
  if (!file || Platform.OS === 'web' || !file.uri.startsWith('file:')) return;
  try {
    const f = new File(file.uri);
    if (f.exists) f.delete();
  } catch {
    // already gone
  }
}
