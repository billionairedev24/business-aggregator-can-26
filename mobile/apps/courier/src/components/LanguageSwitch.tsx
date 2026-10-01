import { useI18n } from '../i18n';
import { Body, Segmented } from './ui';

/** English / Français: each language named in itself. */
export function LanguageSwitch() {
  const { locale, setLocale, t } = useI18n();
  return (
    <>
      <Body strong>{t('account.language')}</Body>
      <Segmented
        label={t('account.language')}
        value={locale}
        onChange={setLocale}
        options={[
          { value: 'en', label: 'English' },
          { value: 'fr-CA', label: 'Français' },
        ]}
      />
    </>
  );
}
