import * as Linking from 'expo-linking';
import { Text } from 'react-native';

import { legalUrl } from '../config';
import { useI18n } from '../i18n';
import { type } from '../ui/primitives';

/**
 * "I agree to the Terms and Privacy Policy. Northline stores data in Canada." with the two documents as links that
 * open outside the app, in the phone's browser (the verbatim design 09/10 pages on the consumer site, S-63).
 */
export function TermsLabel() {
  const { t } = useI18n();
  const open = (doc: 'terms' | 'privacy') => () => void Linking.openURL(legalUrl(doc));
  return (
    <Text style={[type.small, { color: type.muted.color }]}>
      {t('signup.termsBefore')}
      <Text accessibilityRole="link" accessibilityHint={t('common.opensInBrowser')} onPress={open('terms')} style={type.link} testID="link-terms">
        {t('signup.termsLink')}
      </Text>
      {t('signup.termsAnd')}
      <Text accessibilityRole="link" accessibilityHint={t('common.opensInBrowser')} onPress={open('privacy')} style={type.link} testID="link-privacy">
        {t('signup.privacyLink')}
      </Text>
      {t('signup.termsAfter')}
    </Text>
  );
}
