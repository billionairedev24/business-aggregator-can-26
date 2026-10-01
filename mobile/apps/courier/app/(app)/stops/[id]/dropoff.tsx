import { CameraView, useCameraPermissions } from 'expo-camera';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useRef, useState } from 'react';
import { Image, Linking, StyleSheet, TextInput, View } from 'react-native';

import { MIN_TARGET, colors, fonts, radius, randomId, space } from '@northline/mobile-kit';

import type { ProofKind } from '../../../../src/api/courier';
import { SignaturePad } from '../../../../src/components/SignaturePad';
import { Banner, Body, Button, Heading, Row, Screen, Segmented } from '../../../../src/components/ui';
import { useRun } from '../../../../src/hooks';
import { useI18n } from '../../../../src/i18n';
import { keepPhoto, keepSignature } from '../../../../src/proof/files';
import { services } from '../../../../src/services';
import { signaturePng, type Stroke } from '../../../../src/signature/png';
import { pickedUp, stopName } from '../../../../src/stops';

/** Photos are scaled down before they are kept: the api takes up to 5 MB. */
const PHOTO_WIDTH = 1600;

/**
 * Drop-off with proof (S-86): a photo at the door, the customer's signature, or the customer's 4-digit PIN. The proof
 * upload and the drop-off go into the outbox together, in that order, and are sent as soon as there is a connection.
 */
export default function DropoffScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { t } = useI18n();
  const router = useRouter();
  const { run } = useRun();
  const stop = run?.stops.find((s) => s.id === id);
  const [mode, setMode] = useState<ProofKind>('photo');
  const [photo, setPhoto] = useState<string | null>(null);
  const [strokes, setStrokes] = useState<Stroke[]>([]);
  const [padKey, setPadKey] = useState(0);
  const [padSize, setPadSize] = useState({ w: 1, h: 1 });
  const [pin, setPin] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [permission, requestPermission] = useCameraPermissions();
  const camera = useRef<CameraView>(null);

  if (!run || !stop || stop.kind !== 'dropoff') {
    return (
      <Screen>
        <Banner tone="warn">{t('stop.notFound')}</Banner>
      </Screen>
    );
  }
  if (!pickedUp(run, stop)) {
    return (
      <Screen>
        <Banner tone="warn">{t('stop.waitPickup')}</Banner>
      </Screen>
    );
  }

  const takePhoto = async () => {
    setError(null);
    const shot = await camera.current?.takePictureAsync({ quality: 0.8, skipProcessing: false });
    if (!shot) return;
    try {
      const ctx = ImageManipulator.manipulate(shot.uri);
      if ((shot.width ?? 0) > PHOTO_WIDTH) ctx.resize({ width: PHOTO_WIDTH });
      const image = await ctx.renderAsync();
      const saved = await image.saveAsync({ compress: 0.7, format: SaveFormat.JPEG });
      setPhoto(saved.uri);
    } catch {
      setPhoto(shot.uri);
    }
  };

  const complete = async () => {
    setError(null);
    const outbox = services().outbox;
    const key = randomId();
    if (mode === 'pin') {
      if (!/^\d{4}$/.test(pin.trim())) return setError(t('dropoff.pinInvalid'));
      await outbox.enqueue({ kind: 'dropoff', stopId: stop.id, proof: 'pin', pin: pin.trim() });
    } else if (mode === 'photo') {
      if (!photo) return setError(t('dropoff.photoMissing'));
      setBusy(true);
      const file = await keepPhoto(photo, key);
      await outbox.enqueue({ kind: 'proof', stopId: stop.id, proofKind: 'photo', file }, { kind: 'dropoff', stopId: stop.id, proof: 'photo' });
    } else {
      const png = signaturePng(strokes, padSize.w, padSize.h);
      if (!png) return setError(t('dropoff.signatureEmpty'));
      setBusy(true);
      await outbox.enqueue(
        { kind: 'proof', stopId: stop.id, proofKind: 'signature', file: keepSignature(png, key) },
        { kind: 'dropoff', stopId: stop.id, proof: 'signature' },
      );
    }
    setBusy(false);
    router.dismissTo('/run');
  };

  return (
    <Screen testID="dropoff-screen">
      <Heading>{stopName(stop)}</Heading>
      <Body strong>{t('dropoff.how')}</Body>
      <Segmented<ProofKind>
        label={t('dropoff.how')}
        value={mode}
        onChange={(m) => {
          setMode(m);
          setError(null);
        }}
        options={[
          { value: 'photo', label: t('dropoff.photo') },
          { value: 'signature', label: t('dropoff.signature') },
          { value: 'pin', label: t('dropoff.pin') },
        ]}
      />

      {mode === 'photo' ? (
        <>
          <Body muted>{t('dropoff.photoHint')}</Body>
          {photo ? (
            <>
              <Image source={{ uri: photo }} style={styles.preview} accessibilityLabel={t('dropoff.photoTaken')} />
              <Button tone="secondary" label={t('dropoff.retake')} onPress={() => setPhoto(null)} />
            </>
          ) : permission?.granted ? (
            <>
              <View style={styles.preview}>
                <CameraView ref={camera} style={StyleSheet.absoluteFill} facing="back" accessibilityLabel={t('dropoff.cameraLabel')} />
              </View>
              <Button tone="secondary" label={t('dropoff.takePhoto')} onPress={() => void takePhoto()} testID="take-photo" />
            </>
          ) : permission && !permission.canAskAgain ? (
            <Banner tone="warn" action={<Button tone="ghost" label={t('dropoff.openSettings')} onPress={() => void Linking.openSettings()} />}>
              {t('dropoff.cameraDenied')}
            </Banner>
          ) : (
            <Button tone="secondary" label={t('dropoff.allowCamera')} onPress={() => void requestPermission()} testID="allow-camera" />
          )}
        </>
      ) : null}

      {mode === 'signature' ? (
        <>
          <Body muted>{t('dropoff.signatureHint')}</Body>
          <SignaturePad key={padKey} label={t('dropoff.signatureLabel')} onChange={setStrokes} onSize={setPadSize} />
          <Row>
            <Button tone="ghost" label={t('dropoff.clear')} onPress={() => setPadKey((k) => k + 1)} />
          </Row>
        </>
      ) : null}

      {mode === 'pin' ? (
        <>
          <Body muted>{t('dropoff.pinHint')}</Body>
          <Body strong>{t('dropoff.pinLabel')}</Body>
          <TextInput
            value={pin}
            onChangeText={(v) => setPin(v.replace(/\D/g, '').slice(0, 4))}
            keyboardType="number-pad"
            maxLength={4}
            accessibilityLabel={t('dropoff.pinLabel')}
            accessibilityHint={t('dropoff.pinHint')}
            style={[styles.pin, error ? styles.pinError : null]}
            testID="pin"
            autoComplete="off"
            importantForAutofill="no"
          />
        </>
      ) : null}

      {error ? (
        <Banner tone="error">
          {error}
        </Banner>
      ) : null}
      <Button label={t('dropoff.complete')} onPress={() => void complete()} busy={busy} testID="complete-dropoff" />
    </Screen>
  );
}

const styles = StyleSheet.create({
  preview: { width: '100%', aspectRatio: 3 / 4, borderRadius: radius.lg, overflow: 'hidden', backgroundColor: colors.neutral200 },
  pin: {
    minHeight: MIN_TARGET + 16,
    borderWidth: 1,
    borderColor: colors.neutral500,
    borderRadius: radius.md,
    backgroundColor: colors.surface,
    fontFamily: fonts.bodyStrong,
    fontSize: 32,
    letterSpacing: 16,
    textAlign: 'center',
    color: colors.text,
    paddingHorizontal: space[4],
  },
  pinError: { borderColor: colors.accent2, borderWidth: 2 },
});
