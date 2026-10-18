import { useEffect, useId, useState, type ReactNode } from 'react';
import { ArrowSquareOut } from '@phosphor-icons/react';
import { Field, FileButton, RadioGroup, Switch, TextInput } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import { useArrangeSections, useUpdateStorefront, useUploadLogo, type Storefront } from './api';
import { StorefrontPreview } from './StorefrontPreview';
import { serverError } from './PageBuilder';
import { sectionText, useStorefrontT } from './messages';
import { CTA_LABELS, MIN_CONTRAST, SWATCHES, contrastWithWhite } from './sections';
import './PublicPage.css';

export interface PublicPageFormProps {
  storefront: Storefront;
  /** Owner edits; everyone else sees the form read-only. */
  canEdit: boolean;
  /** The announcement, the reward and Publish (the screen's own fields). */
  children?: ReactNode;
}

/**
 * The public page as a form (redesign § 3.11, Provider Hub PH:171 "Public profile"): logo, tagline, brand colour and
 * button label; which parts of the page customers see (on/off, in the recommended order); the live preview beside it.
 * The block builder (reorder, section detail, custom domain) is later; its api stays. Every change saves at once.
 */
export function PublicPageForm({ storefront: s, canEdit, children }: PublicPageFormProps) {
  const t = useStorefrontT();
  const update = useUpdateStorefront(s.merchantId);
  const arrange = useArrangeSections(s.merchantId);
  const logo = useUploadLogo(s.merchantId);
  const ids = { brand: useId(), cta: useId() };
  const [tagline, setTagline] = useState(s.tagline ?? '');
  useEffect(() => setTagline(s.tagline ?? ''), [s.tagline]);
  const taglineError = tagline.trim().length > 80 ? t('taglineTooLong') : serverError(update.error, 'tagline');
  const ratio = contrastWithWhite(s.brandColor);
  const busy = update.isPending || arrange.isPending || logo.isPending;
  const failed = update.isError || arrange.isError || logo.isError;
  const toggle = (kind: Storefront['sections'][number]['kind'], enabled: boolean) =>
    arrange.mutate(s.sections.map(x => ({ kind: x.kind, enabled: x.kind === kind ? enabled : x.enabled })));

  return (
    <div className="nl-pp">
      <div className="nl-pp-main">
        <section className="nl-pp-card" aria-labelledby="pp-look">
          <h2 className="nl-pp-h2" id="pp-look">{t('ppLook')}</h2>
          <div className="nl-field">
            <span className="nl-label">{t('logo')}</span>
            <div className="nl-logo-row">
              <div className="nl-logo-box" style={{ ['--nl-brand' as string]: s.brandColor }}>
                {s.logo ? <img src={s.logo.url} alt={t('logoAlt', { name: s.business.displayName })} /> : (s.business.displayName.charAt(0).toUpperCase() || '·')}
              </div>
              <FileButton accept="image/svg+xml,image/png" disabled={!canEdit} pending={logo.isPending} onFile={f => logo.mutate(f)}>{logo.isPending ? t('uploading') : t('uploadLogo')}</FileButton>
              {s.logo && canEdit ? <button type="button" className="btn btn-ghost" onClick={() => update.mutate({ logoDocumentId: '' })}>{t('removeLogo')}</button> : null}
            </div>
            {logo.error ? <div role="alert" className="nl-error">{logo.error instanceof ValidationError ? logo.error.errors[0]?.message : t('saveError')}</div> : null}
          </div>
          <Field label={t('tagline')} note={t('taglineNote')} error={taglineError}>
            <TextInput value={tagline} maxLength={120} disabled={!canEdit} onChange={e => setTagline(e.target.value)}
              onBlur={() => { if (tagline.trim().length <= 80 && tagline !== (s.tagline ?? '')) update.mutate({ tagline }); }} />
          </Field>
          <div className="nl-field">
            <span className="nl-label" id={ids.brand}>{t('brandColour')}</span>
            <RadioGroup className="nl-swatches" aria-labelledby={ids.brand}>
              {SWATCHES.map(w => (
                <button key={w.key} type="button" role="radio" aria-checked={s.brandColor.toLowerCase() === w.hex} aria-label={t(`sw_${w.key}`)} title={t(`sw_${w.key}`)}
                  className="nl-swatch" style={{ ['--nl-swatch' as string]: w.hex }} disabled={!canEdit} onClick={() => update.mutate({ brandColor: w.hex })} />
              ))}
            </RadioGroup>
            <div className="nl-hint">{t(ratio >= MIN_CONTRAST ? 'contrast' : 'contrastFail', { ratio: ratio.toFixed(1) })}</div>
            {serverError(update.error, 'brandColor') ? <div role="alert" className="nl-error">{serverError(update.error, 'brandColor')}</div> : null}
          </div>
          <div className="nl-field">
            <span className="nl-label" id={ids.cta}>{t('ctaLabelOptions')}</span>
            <RadioGroup className="nl-chips" aria-labelledby={ids.cta}>
              {CTA_LABELS.map(l => <button key={l} type="button" role="radio" aria-checked={s.ctaLabel === l} className="nl-chip" disabled={!canEdit} onClick={() => update.mutate({ ctaLabel: l })}>{t(`cta_${l}`)}</button>)}
            </RadioGroup>
          </div>
          {s.business.about ? <div className="nl-field"><span className="nl-label">{t('ppAbout')}</span><p className="nl-pp-about">{s.business.about}</p><span className="nl-hint">{t('ppAboutNote')}</span></div> : null}
        </section>

        <section className="nl-pp-card" aria-labelledby="pp-parts">
          <h2 className="nl-pp-h2" id="pp-parts">{t('ppParts')}</h2>
          <p className="nl-hint">{t('ppPartsNote')}</p>
          <ul className="nl-pp-parts">
            {s.sections.map(x => (
              <li key={x.kind} className="nl-pp-part">
                <span className="nl-pp-part-text"><strong>{sectionText(t, x.kind, 'name')}</strong><span>{sectionText(t, x.kind, x.enabled || x.required ? 'on' : 'off')}</span></span>
                {x.required ? <span className="tag tag-neutral">{t('stateAlways')}</span>
                  : <Switch checked={x.enabled} disabled={!canEdit || arrange.isPending} label={t('ppShow', { name: sectionText(t, x.kind, 'name') })} onChange={on => toggle(x.kind, on)} />}
              </li>
            ))}
          </ul>
        </section>

        <section className="nl-pp-card" aria-labelledby="pp-publish">
          <h2 className="nl-pp-h2" id="pp-publish">{t('ppPublish')}</h2>
          {children}
        </section>
        <div className="nl-hint" role="status" aria-live="polite">{busy ? t('saving') : failed ? t('saveError') : ''}</div>
      </div>

      <aside className="nl-pp-preview" aria-label={t('preview')}>
        <span className="nl-pp-caption">{t('preview')}</span>
        <StorefrontPreview storefront={s} device="phone" />
        <a className="nl-pp-live" href={`https://${s.url}`} target="_blank" rel="noopener">{t('ppOpenLive')} <ArrowSquareOut size={16} aria-hidden /></a>
        <span className="nl-hint">{t('previewNote')}</span>
      </aside>
    </div>
  );
}
