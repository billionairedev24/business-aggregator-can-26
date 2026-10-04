import { CameraView, useCameraPermissions } from 'expo-camera';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useRef, useState } from 'react';
import { Image, Linking, StyleSheet, TextInput, View } from 'react-native';

import { MIN_TARGET, colors, fonts, radius, randomId, space } from '@northline/mobile-kit';

import { REFUSE_REASONS, type IdCheckAnswer, type ProofKind, type RefuseReason } from '../../../../src/api/courier';
import { SignaturePad } from '../../../../src/components/SignaturePad';
import { Banner, Body, Button, Card, Check, Heading, Row, Screen, Segmented } from '../../../../src/components/ui';
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
 *
 * 2026-10-04 age-restricted orders: before the proof, the courier confirms they checked government photo ID, that it
 * is the person who ordered (the name the api gives) and that they are of age — three yes/no answers, never the ID
 * itself. Can't hand it over (a reason) → the order goes back to the business: a return stop on the run.
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
  const [answers, setAnswers] = useState<IdCheckAnswer>({ idChecked: false, recipientMatches: false, ofAge: false });
  const [refusing, setRefusing] = useState(false);
  const [reason, setReason] = useState<RefuseReason | null>(null);
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

  const idCheck = stop.idCheck ? answers : undefined;
  const confirmed = !idCheck || (idCheck.idChecked && idCheck.recipientMatches && idCheck.ofAge);
  const toggle = (k: keyof IdCheckAnswer) => {
    setAnswers((a) => ({ ...a, [k]: !a[k] }));
    setError(null);
  };

  const refuse = async () => {
    if (!reason) return setError(t('idcheck.chooseReason'));
    setBusy(true);
    await services().outbox.enqueue({ kind: 'refuse', stopId: stop.id, reason });
    setBusy(false);
    router.dismissTo('/run');
  };

  const complete = async () => {
    setError(null);
    if (!confirmed) return setError(t('idcheck.confirmAll'));
    const outbox = services().outbox;
    const key = randomId();
    if (mode === 'pin') {
      if (!/^\d{4}$/.test(pin.trim())) return setError(t('dropoff.pinInvalid'));
      await outbox.enqueue({ kind: 'dropoff', stopId: stop.id, proof: 'pin', pin: pin.trim(), ...(idCheck ? { idCheck } : {}) });
    } else if (mode === 'photo') {
      if (!photo) return setError(t('dropoff.photoMissing'));
      setBusy(true);
      const file = await keepPhoto(photo, key);
      await outbox.enqueue({ kind: 'proof', stopId: stop.id, proofKind: 'photo', file }, { kind: 'dropoff', stopId: stop.id, proof: 'photo', ...(idCheck ? { idCheck } : {}) });
    } else {
      const png = signaturePng(strokes, padSize.w, padSize.h);
      if (!png) return setError(t('dropoff.signatureEmpty'));
      setBusy(true);
      await outbox.enqueue(
        { kind: 'proof', stopId: stop.id, proofKind: 'signature', file: keepSignature(png, key) },
        { kind: 'dropoff', stopId: stop.id, proof: 'signature', ...(idCheck ? { idCheck } : {}) },
      );
    }
    setBusy(false);
    router.dismissTo('/run');
  };

  if (stop.idCheck && refusing) {
    return (
      <Screen testID="refuse-screen">
        <Heading>{t('idcheck.refuseTitle')}</Heading>
        <View accessibilityRole="radiogroup" accessibilityLabel={t('idcheck.refuseTitle')} style={styles.list}>
          {REFUSE_REASONS.map((r) => (
            <Check key={r} role="radio" label={t(`idcheck.reason.${r}`)} on={reason === r} onPress={() => { setReason(r); setError(null); }} testID={`reason-${r}`} />
          ))}
        </View>
        <Body muted>{t('idcheck.refuseNote')}</Body>
        {error ? <Banner tone="error">{error}</Banner> : null}
        <Button tone="danger" label={t('idcheck.refuseConfirm')} onPress={() => void refuse()} busy={busy} testID="confirm-refuse" />
        <Button tone="ghost" label={t('idcheck.cancel')} onPress={() => { setRefusing(false); setError(null); }} />
      </Screen>
    );
  }

  return (
    <Screen testID="dropoff-screen">
      <Heading>{stopName(stop)}</Heading>
      {stop.idCheck ? (
        <Card testID="id-check">
          <Body strong>{t('idcheck.title')}</Body>
          <Body>{stop.idCheck.recipient ? t('idcheck.why', { age: stop.idCheck.age }) : t('idcheck.whyNoName', { age: stop.idCheck.age })}</Body>
          {stop.idCheck.recipient ? <Body strong>{t('idcheck.name', { name: stop.idCheck.recipient })}</Body> : null}
          <Check label={t('idcheck.checked')} on={answers.idChecked} onPress={() => toggle('idChecked')} testID="id-checked" />
          <Check label={t('idcheck.matches')} on={answers.recipientMatches} onPress={() => toggle('recipientMatches')} testID="id-matches" />
          <Check label={t('idcheck.ofAge', { age: stop.idCheck.age })} on={answers.ofAge} onPress={() => toggle('ofAge')} testID="id-of-age" />
          <Body muted>{t('idcheck.private')}</Body>
          <Button tone="danger" label={t('idcheck.refuse')} onPress={() => { setRefusing(true); setError(null); }} testID="refuse" />
        </Card>
      ) : null}
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
  list: { gap: space[2] },
});
