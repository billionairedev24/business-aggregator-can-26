import { useEffect, useId, useState, type ReactNode } from 'react';
import { ChipTabs, Field, FileButton, TextInput } from '@northline/ui';
import { ValidationError } from '../../lib/http';
import type { MerchantType } from '../shell/api';
import { useArrangeSections, useUpdateStorefront, useUploadLogo, useVerifyDomain, type SectionState, type Storefront } from './api';
import { SectionList } from './SectionList';
import { StorefrontPreview } from './StorefrontPreview';
import { sectionText, useStorefrontT } from './messages';
import { CTA_LABELS, DEFAULT_ORDER, MIN_CONTRAST, SWATCHES, contrastWithWhite, type SectionKind } from './sections';
import './PageBuilder.css';

export interface PageBuilderProps {
  storefront: Storefront;
  /** Owner edits; everyone else sees the builder read-only. */
  canEdit: boolean;
  /** `onboarding`: step 5 (full detail panel). `studio`: Business page / Store / Menu page screen. */
  variant: 'onboarding' | 'studio';
  /** Extra fields under the builder (Studio: announcement, reward, Publish …). */
  children?: ReactNode;
}

/**
 * The page builder shared by onboarding step 5 and Studio → Business page / Store / Menu page (design 02 lines
 * 198–249 and 767–785): brand swatches with the 4.5:1 note, logo, tagline, sections (drag, arrows, switches, detail
 * panel, CTA label, reset), custom domain with CNAME state, and the live Phone / Web preview. Every change saves at
 * once (sections: one PATCH with the full list, optimistic).
 */
export function PageBuilder({ storefront: s, canEdit, variant, children }: PageBuilderProps) {
  const t = useStorefrontT();
  const type: MerchantType = s.business.type;
  const update = useUpdateStorefront(s.merchantId);
  const arrange = useArrangeSections(s.merchantId);
  const logo = useUploadLogo(s.merchantId);
  const verify = useVerifyDomain(s.merchantId);
  const [selected, setSelected] = useState<SectionKind>(s.sections[0]?.kind ?? 'hero');
  const [device, setDevice] = useState<'phone' | 'web'>('phone');
  const [announce, setAnnounce] = useState('');
  const ids = { tagline: useId(), domain: useId(), sections: useId() };

  const [tagline, setTagline] = useState(s.tagline ?? '');
  const [domain, setDomain] = useState(s.customDomain ?? '');
  useEffect(() => setTagline(s.tagline ?? ''), [s.tagline]);
  useEffect(() => setDomain(s.customDomain ?? ''), [s.customDomain]);
  const taglineError = tagline.trim().length > 80 ? t('taglineTooLong') : serverError(update.error, 'tagline');
  const domainError = serverError(update.error, 'customDomain');

  const onCount = s.sections.filter(x => x.enabled).length;
  const arrangeTo = (next: SectionState[], moved?: { kind: SectionKind; to: number }) => {
    arrange.mutate(next);
    if (moved) setAnnounce(t('moved', { name: sectionText(t, moved.kind, 'name'), pos: moved.to + 1, total: next.length }));
  };
  const reset = () => arrangeTo(DEFAULT_ORDER[type].map(kind => ({ kind, enabled: true })));
  const sel = s.sections.find(x => x.kind === selected) ?? s.sections[0]!;
  const ratio = contrastWithWhite(s.brandColor);
  const busy = update.isPending || arrange.isPending || logo.isPending || verify.isPending;
  const failed = update.isError || arrange.isError || logo.isError;

  return (
    <div className="nl-builder">
      <div className="nl-builder-main">
        <div className="nl-grid" style={{ ['--nl-min' as string]: '200px' }}>
          <div className="nl-field">
            <span className="nl-label" id={`${ids.sections}-brand`}>{t('brandColour')}</span>
            <div className="nl-swatches" role="radiogroup" aria-labelledby={`${ids.sections}-brand`}>
              {SWATCHES.map(w => (
                <button
                  key={w.key}
                  type="button"
                  role="radio"
                  aria-checked={s.brandColor.toLowerCase() === w.hex}
                  aria-label={t(`sw_${w.key}`)}
                  title={t(`sw_${w.key}`)}
                  className="nl-swatch"
                  style={{ ['--nl-swatch' as string]: w.hex }}
                  disabled={!canEdit}
                  onClick={() => update.mutate({ brandColor: w.hex })}
                />
              ))}
            </div>
            <div className="nl-hint">{t(ratio >= MIN_CONTRAST ? 'contrast' : 'contrastFail', { ratio: ratio.toFixed(1) })}</div>
            {serverError(update.error, 'brandColor') ? <div role="alert" className="nl-error">{serverError(update.error, 'brandColor')}</div> : null}
          </div>
          <div className="nl-field">
            <span className="nl-label">{t('logo')}</span>
            <div className="nl-logo-row">
              <div className="nl-logo-box" style={{ ['--nl-brand' as string]: s.brandColor }}>
                {s.logo ? <img src={s.logo.url} alt={t('logoAlt', { name: s.business.displayName })} /> : (s.business.displayName.charAt(0).toUpperCase() || 'N')}
              </div>
              <FileButton accept="image/svg+xml,image/png" disabled={!canEdit} pending={logo.isPending} onFile={f => logo.mutate(f)}>{logo.isPending ? t('uploading') : t('uploadLogo')}</FileButton>
              {s.logo && canEdit ? <button type="button" className="btn btn-ghost" onClick={() => update.mutate({ logoDocumentId: '' })}>{t('removeLogo')}</button> : null}
            </div>
            {logo.error ? <div role="alert" className="nl-error">{logo.error instanceof ValidationError ? logo.error.errors[0]?.message : t('saveError')}</div> : null}
          </div>
          <Field label={t('tagline')} note={t('taglineNote')} error={taglineError} span>
            <TextInput
              id={ids.tagline}
              value={tagline}
              maxLength={120}
              disabled={!canEdit}
              onChange={e => setTagline(e.target.value)}
              onBlur={() => { if (tagline.trim().length <= 80 && tagline !== (s.tagline ?? '')) update.mutate({ tagline }); }}
            />
          </Field>
        </div>

        <div>
          <div className="nl-sections-head">
            <span className="nl-sections-label" id={ids.sections}>{t('sectionsLabel', { on: onCount, total: s.sections.length })}</span>
            <span className="nl-hint">{t('sectionsHint')}</span>
          </div>
          <SectionList sections={s.sections} selected={sel.kind} onSelect={setSelected} onChange={arrangeTo} disabled={!canEdit} />
          <div className="nl-sr-only" aria-live="polite">{announce}</div>
          <SectionDetail section={sel} variant={variant} ctaLabel={s.ctaLabel} canEdit={canEdit} onCta={label => update.mutate({ ctaLabel: label })} />
          <div className="nl-builder-reset">
            <button type="button" className="btn btn-ghost" disabled={!canEdit} onClick={reset}>{t('reset')}</button>
            <span className="nl-hint">{t('soon')}</span>
          </div>
        </div>

        <div className="nl-field">
          <label className="nl-label" htmlFor={ids.domain}>{t('customDomain')}</label>
          <div className="nl-domain-row">
            <TextInput
              id={ids.domain}
              placeholder={t(`domainPh_${type}`)}
              value={domain}
              disabled={!canEdit}
              aria-invalid={domainError ? true : undefined}
              aria-describedby={`${ids.domain}-hint`}
              onChange={e => setDomain(e.target.value)}
              onBlur={() => { if (domain.trim() !== (s.customDomain ?? '')) update.mutate({ customDomain: domain.trim() }); }}
              onKeyDown={e => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }}
            />
            {s.customDomain && s.customDomainStatus !== 'verified' && canEdit
              ? <button type="button" className="btn btn-secondary" disabled={verify.isPending} onClick={() => verify.mutate()}>{t('checkNow')}</button>
              : null}
          </div>
          {domainError ? <div role="alert" className="nl-error">{domainError}</div> : null}
          {s.customDomain && s.customDomainStatus ? <div className="nl-hint" data-status={s.customDomainStatus} role="status">{t(`domain_${s.customDomainStatus}`)}</div> : null}
          <div className="nl-hint" id={`${ids.domain}-hint`}>{t('domainHint')}</div>
        </div>

        {children}

        <div className="nl-hint" role="status" aria-live="polite">{busy ? t('saving') : failed ? t('saveError') : ''}</div>
      </div>

      <div className="nl-builder-preview">
        <div className="nl-pv-head">
          <span className="nl-pv-caption">{t('preview')}</span>
          <ChipTabs aria-label={t('previewMode')} value={device} onChange={setDevice} options={[{ value: 'phone', label: t('phone') }, { value: 'web', label: t('web') }]} />
        </div>
        <StorefrontPreview storefront={s} device={device} />
        <div className="nl-hint" style={{ marginTop: 10 }}>{t('previewNote')}</div>
      </div>
    </div>
  );
}

function SectionDetail({ section, variant, ctaLabel, canEdit, onCta }: { section: Storefront['sections'][number]; variant: PageBuilderProps['variant']; ctaLabel: string; canEdit: boolean; onCta: (label: string) => void }) {
  const t = useStorefrontT();
  const k = section.kind;
  const state = section.required ? t('stateAlways') : section.enabled ? t('stateOn') : t('stateOff');
  return (
    <div className="nl-section-detail" aria-live="polite">
      <div className="nl-section-detail-head"><strong>{sectionText(t, k, 'name')}</strong><span>{state}</span></div>
      {variant === 'onboarding' && <div className="nl-section-detail-line"><strong>{t('shows')}</strong> {sectionText(t, k, 'shows')}</div>}
      <div className="nl-section-detail-line"><strong>{variant === 'onboarding' ? t('whenOn') : t('onShort')}</strong> {sectionText(t, k, 'on')}</div>
      <div className="nl-section-detail-line"><strong>{variant === 'onboarding' ? t('whenOff') : t('offShort')}</strong> {sectionText(t, k, 'off')}</div>
      {variant === 'onboarding' && <div className="nl-section-detail-line"><strong>{t('source')}</strong> {sectionText(t, k, 'source')}</div>}
      {k === 'cta' && (
        <div className="nl-chips" role="radiogroup" aria-label={t('ctaLabelOptions')} style={{ marginTop: 10 }}>
          {CTA_LABELS.map(l => (
            <button key={l} type="button" role="radio" aria-checked={ctaLabel === l} className="nl-chip" disabled={!canEdit} onClick={() => onCta(l)}>{t(`cta_${l}`)}</button>
          ))}
        </div>
      )}
    </div>
  );
}

/** First server 422 message for `field`, if the last save failed on it. */
export function serverError(error: unknown, field: string): string | undefined {
  return error instanceof ValidationError ? error.errors.find(e => e.field === field)?.message : undefined;
}

