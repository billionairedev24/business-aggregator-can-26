import { useState } from 'react';
import { Image, StyleSheet, Text, View } from 'react-native';

import { ApiError, checkPhoto, colors, photoForm, radius, randomId, space, type PickedImage } from '@northline/mobile-kit';

import { JOB_PHOTO_MAX, JOB_PHOTO_MAX_BYTES } from '../api/hire';
import { useI18n } from '../i18n';
import { hire } from '../messages/Messages';
import { Body, Button, type } from '../ui/primitives';
import { errorMessage } from '../ui/states';
import { pickImage } from './pick';

/** The phone sends JPEG or PNG (the system picker hands HEIC over as JPEG); the api also takes HEIC and WebP from the web. */
const APP_TYPES: readonly string[] = ['image/jpeg', 'image/png'];

interface Photo {
  key: string;
  image: PickedImage;
  id?: string;
}

/**
 * S-153: photos of the job on a quote request — from the camera or the photo library, checked on the phone (type,
 * 10 MB), uploaded at once (`POST /me/photos`: the api checks them again and runs the platform's scanner), then sent
 * with the request (`photoIds`). Only the person and the businesses asked can see them.
 */
export function useJobPhotos() {
  const { t } = useI18n();
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [error, setError] = useState<string>();
  const add = async (source: 'camera' | 'library') => {
    setError(undefined);
    if (photos.length >= JOB_PHOTO_MAX) return setError(t('hire.photos.max', { max: JOB_PHOTO_MAX }));
    const picked = await pickImage(source, 0.8).catch(() => null);
    if (picked === 'denied') return setError(t('shop.problem.cameraDenied'));
    if (!picked) return;
    const problem = checkPhoto(picked, APP_TYPES, JOB_PHOTO_MAX_BYTES);
    if (problem) return setError(t(problem === 'size' ? 'hire.photos.size' : 'hire.photos.type'));
    const key = randomId();
    setPhotos((p) => [...p, { key, image: picked }]);
    try {
      const uploaded = await hire().uploadPhoto(photoForm(picked, `job-${photos.length + 1}`));
      setPhotos((p) => p.map((x) => (x.key === key ? { ...x, id: uploaded.id } : x)));
    } catch (e) {
      setPhotos((p) => p.filter((x) => x.key !== key));
      setError(e instanceof ApiError && e.errors.length ? e.errors.map((x) => x.message).join(' ') : errorMessage(e, t));
    }
  };
  return {
    photos,
    error,
    add,
    remove: (key: string) => setPhotos((p) => p.filter((x) => x.key !== key)),
    uploading: photos.some((p) => !p.id),
    ids: photos.flatMap((p) => (p.id ? [p.id] : [])),
  };
}

export function JobPhotos({ state }: { state: ReturnType<typeof useJobPhotos> }) {
  const { t } = useI18n();
  return (
    <View style={styles.stack}>
      <Text style={[type.body, type.strong]}>{t('hire.photos.title')}</Text>
      <Body tone="small">{t('hire.photos.hint', { max: JOB_PHOTO_MAX })}</Body>
      {state.photos.length ? (
        <View style={styles.photos}>
          {state.photos.map((p, n) => (
            <View key={p.key} style={styles.item}>
              <Image source={{ uri: p.image.uri }} style={styles.photo} accessibilityLabel={t(p.id ? 'shop.problem.photoN' : 'shop.problem.photoSending', { n: n + 1 })} testID={`job-photo-${n}`} />
              <Button label={t('shop.problem.photoRemove')} tone="ghost" onPress={() => state.remove(p.key)} testID={`job-photo-remove-${n}`} />
            </View>
          ))}
        </View>
      ) : null}
      {state.photos.length < JOB_PHOTO_MAX ? (
        <View style={styles.row}>
          <Button label={t('shop.problem.takePhoto')} tone="secondary" onPress={() => void state.add('camera')} testID="job-photo-camera" />
          <Button label={t('shop.problem.choosePhoto')} tone="secondary" onPress={() => void state.add('library')} testID="job-photo-library" />
        </View>
      ) : null}
      {state.error ? (
        <Text accessibilityRole="alert" style={type.error} testID="job-photo-error">
          {state.error}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  stack: { gap: space[2] },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  photos: { flexDirection: 'row', flexWrap: 'wrap', gap: space[2] },
  item: { alignItems: 'center', gap: 2 },
  photo: { width: 88, height: 88, borderRadius: radius.md, backgroundColor: colors.neutral200 },
});
