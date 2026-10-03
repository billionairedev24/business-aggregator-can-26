import { useId, useState, type FormEvent } from 'react';
import { ChatCircleDots, X } from '@phosphor-icons/react';
import clsx from 'clsx';
import { Button } from './Button';
import { Field, Select, TextArea } from './Field';
import { FileButton } from './FileButton';
import { Dialog } from './Overlay';
import { defineMessages } from './i18n';

/**
 * S-121 pilot feedback: the "Send feedback" control the Studio, the consumer web and the console show to pilot
 * participants (the app asks `GET /api/v1/me/pilot` first and renders nothing for everyone else). The app sends; this
 * component only gathers what the person chose and typed, plus an optional screenshot — taken with the browser's own
 * screen capture (the person picks what to share) or attached as a PNG/JPEG file. Context (screen, version, language,
 * device) comes from `feedbackContext()`, never from the page's query string.
 */
export type FeedbackCategory = 'bug' | 'confusing' | 'idea' | 'praise';
export type FeedbackSeverity = 'blocker' | 'major' | 'minor' | 'cosmetic';
export const FEEDBACK_CATEGORIES: readonly FeedbackCategory[] = ['bug', 'confusing', 'idea', 'praise'];
export const FEEDBACK_SEVERITIES: readonly FeedbackSeverity[] = ['blocker', 'major', 'minor', 'cosmetic'];
export const FEEDBACK_MAX_CHARS = 4000;
export const SCREENSHOT_TYPES = ['image/png', 'image/jpeg'] as const;
export const SCREENSHOT_MAX_BYTES = 5 * 1024 * 1024;

export interface PilotFeedbackDraft { category: FeedbackCategory; severity: FeedbackSeverity; body: string; screenshot?: Blob }

export interface PilotFeedbackProps {
  /** Sends the draft (uploads the screenshot first); resolves with the reference ("UAT-1001"), rejects with a message to show. */
  onSubmit: (draft: PilotFeedbackDraft) => Promise<{ reference: string }>;
  /** The server's limit (`screenshotMaxBytes`); 5 MB by default. */
  maxScreenshotBytes?: number;
  /** Fixed in the corner of the viewport (default) or inline where the app puts it (a menu, a settings row). */
  placement?: 'corner' | 'inline';
  className?: string;
}

const useT = defineMessages({
  en: {
    open: 'Send feedback', title: 'Send feedback to the Northline team', lede: 'You’re in the pilot. Tell us what worked and what didn’t — the team reads every note.',
    category: 'What is it?', c_bug: 'Something’s broken', c_confusing: 'Something’s confusing', c_idea: 'An idea', c_praise: 'Something I liked',
    severity: 'How much did it get in your way?', s_blocker: 'I couldn’t finish what I was doing', s_major: 'I finished, but it was hard', s_minor: 'A small annoyance', s_cosmetic: 'Just how it looks',
    body: 'What happened?', bodyHint: 'What you did, what you expected, what you saw. Don’t include passwords, card numbers or codes.',
    bodyRequired: 'Tell us what happened, in 1 to 4,000 characters.',
    screenshot: 'Screenshot · optional', capture: 'Capture this screen', attach: 'Attach an image', remove: 'Remove the screenshot',
    shotHint: 'PNG or JPEG, up to {mb} MB. The screen, app version, language and browser are sent with your note — nothing else.',
    shotType: 'Attach the screenshot as a PNG or JPEG image.', shotSize: 'The screenshot must be {mb} MB or smaller.', captureFailed: 'The screen wasn’t captured. You can attach an image instead.',
    attached: 'Attached: {name} ({kb} KB)', cancel: 'Cancel', send: 'Send', sending: 'Sending…',
    sent: 'Thank you — we got it as {reference}. You can follow it from this button.', close: 'Close', another: 'Send another',
  },
  fr: {
    open: 'Envoyer un commentaire', title: 'Envoyer un commentaire à l’équipe Northline', lede: 'Vous faites partie du pilote. Dites-nous ce qui a marché et ce qui n’a pas marché — l’équipe lit chaque note.',
    category: 'De quoi s’agit-il?', c_bug: 'Quelque chose est brisé', c_confusing: 'Quelque chose est déroutant', c_idea: 'Une idée', c_praise: 'Quelque chose que j’ai aimé',
    severity: 'À quel point cela vous a-t-il gêné?', s_blocker: 'Je n’ai pas pu terminer', s_major: 'J’ai terminé, mais difficilement', s_minor: 'Un petit irritant', s_cosmetic: 'Seulement l’apparence',
    body: 'Que s’est-il passé?', bodyHint: 'Ce que vous avez fait, ce que vous attendiez, ce que vous avez vu. N’incluez pas de mot de passe, de numéro de carte ni de code.',
    bodyRequired: 'Dites-nous ce qui s’est passé, en 1 à 4 000 caractères.',
    screenshot: 'Capture d’écran · facultatif', capture: 'Capturer cet écran', attach: 'Joindre une image', remove: 'Retirer la capture',
    shotHint: 'PNG ou JPEG, jusqu’à {mb} Mo. L’écran, la version de l’application, la langue et le navigateur sont envoyés avec votre note — rien d’autre.',
    shotType: 'Joignez la capture d’écran en image PNG ou JPEG.', shotSize: 'La capture d’écran doit faire {mb} Mo ou moins.', captureFailed: 'L’écran n’a pas été capturé. Vous pouvez joindre une image à la place.',
    attached: 'Jointe : {name} ({kb} Ko)', cancel: 'Annuler', send: 'Envoyer', sending: 'Envoi…',
    sent: 'Merci — nous l’avons reçu sous le numéro {reference}. Vous pouvez le suivre depuis ce bouton.', close: 'Fermer', another: 'En envoyer un autre',
  },
});

/** The client-side screenshot checks (the server repeats them with the file's magic bytes and pixel size, S-104). */
export function screenshotProblem(file: { type: string; size: number }, maxBytes = SCREENSHOT_MAX_BYTES): 'type' | 'size' | undefined {
  if (!(SCREENSHOT_TYPES as readonly string[]).includes(file.type)) return 'type';
  return file.size > maxBytes ? 'size' : undefined;
}

/** "Firefox 131 · macOS" from a user agent: browser and major version, operating system. Nothing else of the UA is sent. */
export function describePlatform(ua: string): string {
  const browsers: [string, RegExp][] = [['Edge', /Edg\/(\d+)/], ['Firefox', /Firefox\/(\d+)/], ['Chrome', /Chrome\/(\d+)/], ['Safari', /Version\/(\d+).*Safari/]];
  const found = browsers.map(([name, re]) => [name, re.exec(ua)?.[1]] as const).find(([, v]) => v);
  const browser = found ? `${found[0]} ${found[1]}` : 'Browser';
  const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad|iPod/.test(ua) ? 'iOS' : /Mac OS X|Macintosh/.test(ua) ? 'macOS' : /Windows/.test(ua) ? 'Windows' : /Linux/.test(ua) ? 'Linux' : 'Other OS';
  return `${browser} · ${os}`;
}

/** The screen the person is on, as a path: no query string or fragment (they can hold tokens), no host. */
export function currentRoute(loc: { pathname: string } = window.location): string {
  return loc.pathname || '/';
}

/** What every app sends with the note. `appVersion` is the build's (`VITE_NL_APP_VERSION`, else "dev"). */
export function feedbackContext(appVersion: string | undefined, locale: string) {
  return {
    route: currentRoute(),
    appVersion: appVersion && /^[A-Za-z0-9._+-]{1,40}$/.test(appVersion) ? appVersion : 'dev',
    locale: locale === 'fr' ? 'fr-CA' : locale === 'en' ? 'en-CA' : locale,
    platform: typeof navigator === 'undefined' ? 'Unknown' : describePlatform(navigator.userAgent),
  };
}

/** One frame of what the person chose to share, as PNG (JPEG when the PNG is over the limit). */
async function captureScreen(maxBytes: number): Promise<Blob> {
  const stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
  try {
    const video = document.createElement('video');
    video.muted = true;
    video.srcObject = stream;
    await video.play();
    const canvas = document.createElement('canvas');
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    canvas.getContext('2d')?.drawImage(video, 0, 0);
    const blob = (type: string, q?: number) => new Promise<Blob | null>(res => canvas.toBlob(res, type, q));
    const png = await blob('image/png');
    if (png && png.size <= maxBytes) return png;
    const jpeg = await blob('image/jpeg', 0.8);
    if (!jpeg) throw new Error('capture');
    return jpeg;
  } finally {
    stream.getTracks().forEach(t => t.stop());
  }
}

const canCapture = () => typeof navigator !== 'undefined' && typeof navigator.mediaDevices?.getDisplayMedia === 'function';

export function PilotFeedback({ onSubmit, maxScreenshotBytes = SCREENSHOT_MAX_BYTES, placement = 'corner', className }: PilotFeedbackProps) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [hidden, setHidden] = useState(false);
  const [category, setCategory] = useState<FeedbackCategory>('bug');
  const [severity, setSeverity] = useState<FeedbackSeverity>('minor');
  const [body, setBody] = useState('');
  const [shot, setShot] = useState<{ blob: Blob; name: string }>();
  const [errors, setErrors] = useState<{ body?: string; shot?: string; form?: string }>({});
  const [pending, setPending] = useState(false);
  const [sent, setSent] = useState<string>();
  const legend = useId();
  const mb = Math.round(maxScreenshotBytes / 1024 / 1024);

  const reset = () => { setCategory('bug'); setSeverity('minor'); setBody(''); setShot(undefined); setErrors({}); setSent(undefined); };
  const close = () => { setOpen(false); reset(); };
  const take = (blob: Blob, name: string) => {
    const problem = screenshotProblem(blob, maxScreenshotBytes);
    if (problem) { setErrors(e => ({ ...e, shot: problem === 'type' ? t('shotType') : t('shotSize', { mb }) })); return; }
    setErrors(e => ({ ...e, shot: undefined }));
    setShot({ blob, name });
  };
  const capture = async () => {
    setHidden(true); // keep the dialog out of the picture
    try { take(await captureScreen(maxScreenshotBytes), 'screen.png'); }
    catch { setErrors(e => ({ ...e, shot: t('captureFailed') })); }
    finally { setHidden(false); }
  };
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const text = body.trim();
    if (!text || text.length > FEEDBACK_MAX_CHARS) { setErrors(x => ({ ...x, body: t('bodyRequired') })); return; }
    setPending(true);
    setErrors({});
    try {
      const { reference } = await onSubmit({ category, severity, body: text, screenshot: shot?.blob });
      setSent(reference);
    } catch (err) {
      setErrors({ form: err instanceof Error ? err.message : String(err) });
    } finally {
      setPending(false);
    }
  };

  return (
    <>
      <button type="button" className={clsx('nl-feedback-open', placement === 'corner' && 'nl-feedback-corner', className)} onClick={() => setOpen(true)}>
        <ChatCircleDots weight="duotone" aria-hidden /> {t('open')}
      </button>
      <Dialog open={open && !hidden} onClose={close} title={t('title')} width={560}
        actions={sent ? <>
          <Button type="button" variant="ghost" onClick={reset}>{t('another')}</Button>
          <Button type="button" onClick={close}>{t('close')}</Button>
        </> : <>
          <Button type="button" variant="ghost" onClick={close}>{t('cancel')}</Button>
          <Button type="submit" form={legend} disabled={pending} aria-busy={pending || undefined}>{pending ? t('sending') : t('send')}</Button>
        </>}>
        {sent ? <p role="status">{t('sent', { reference: sent })}</p> : (
          <form id={legend} className="nl-feedback-form" onSubmit={submit} noValidate>
            <p className="nl-hint">{t('lede')}</p>
            <fieldset className="nl-feedback-kinds">
              <legend className="nl-label">{t('category')}</legend>
              {FEEDBACK_CATEGORIES.map(c => (
                <label key={c} className={clsx('nl-feedback-kind', category === c && 'is-selected')}>
                  <input type="radio" name="category" value={c} checked={category === c} onChange={() => setCategory(c)} /> {t(`c_${c}`)}
                </label>
              ))}
            </fieldset>
            <Field label={t('severity')}>
              <Select value={severity} onChange={e => setSeverity(e.target.value as FeedbackSeverity)}
                options={FEEDBACK_SEVERITIES.map(s => ({ value: s, label: t(`s_${s}`) }))} />
            </Field>
            <Field label={t('body')} hint={t('bodyHint')} error={errors.body}>
              <TextArea rows={5} value={body} maxLength={FEEDBACK_MAX_CHARS} onChange={e => setBody(e.target.value)} />
            </Field>
            <div className="nl-field">
              <span className="nl-label">{t('screenshot')}</span>
              <div className="nl-feedback-shot">
                {canCapture() ? <Button type="button" variant="secondary" onClick={() => void capture()}>{t('capture')}</Button> : null}
                <FileButton accept={SCREENSHOT_TYPES.join(',')} onFile={f => take(f, f.name)} variant="ghost">{t('attach')}</FileButton>
              </div>
              {shot ? (
                <p className="nl-feedback-attached">
                  {t('attached', { name: shot.name, kb: Math.max(1, Math.round(shot.blob.size / 1024)) })}
                  <button type="button" className="nl-feedback-remove" aria-label={t('remove')} onClick={() => setShot(undefined)}><X aria-hidden /></button>
                </p>
              ) : null}
              {errors.shot ? <div role="alert" className="nl-error">{errors.shot}</div> : null}
              <div className="nl-hint">{t('shotHint', { mb })}</div>
            </div>
            {errors.form ? <div role="alert" className="nl-error">{errors.form}</div> : null}
          </form>
        )}
      </Dialog>
    </>
  );
}
