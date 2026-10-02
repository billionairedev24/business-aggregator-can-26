import { useI18n } from '../src/i18n';
import { BrowserSignIn } from '../src/auth/BrowserSignIn';
import { Body } from '../src/ui/primitives';
import { Screen } from '../src/ui/screen';

/** Sign in (S-97: in the browser, on the consumer site's page; S-98 adds the in-app phone + code sign-in). */
export default function SignIn() {
  const { t } = useI18n();
  return (
    <Screen title={t('title.signin')} testID="sign-in">
      <Body tone="lead">{t('browserSignIn.body')}</Body>
      <BrowserSignIn tone="primary" />
    </Screen>
  );
}
