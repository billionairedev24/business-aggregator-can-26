import { useLocalSearchParams } from 'expo-router';

import { useAuth } from '../auth/AuthProvider';
import { useI18n, type MessageKey } from '../i18n';
import { SCREENS, type ScreenKey } from '../screens';
import { Body, Title } from './primitives';
import { Screen } from './screen';
import { SignInPrompt } from './states';

/**
 * A screen of journeys B–D before its story builds it (S-97): the design's header or name, which story builds it and
 * the api it will use (src/screens.ts). Personal screens already show guests the sign-in prompt. Each story replaces
 * its route files' `<Stub screen="…" />` with the real screen.
 */
export function Stub({ screen }: { screen: ScreenKey }) {
  const { t } = useI18n();
  const { status } = useAuth();
  const def = SCREENS[screen];
  const params = useLocalSearchParams<{ id?: string }>();
  const titleKey = `title.${screen}` as MessageKey;
  const name = t(`screen.${screen}` as MessageKey);
  const header = def.tab ? undefined : t(titleKey, { ref: params.id ?? '' }) === titleKey ? name : t(titleKey, { ref: params.id ?? '' });
  return (
    <Screen title={header} back={!def.tab} testID={`stub-${screen}`}>
      {def.tab ? <Title>{name}</Title> : null}
      {def.personal && status !== 'signedIn' ? (
        <SignInPrompt message={t('stub.body', { story: def.story })} />
      ) : (
        <>
          <Body tone="muted">{t('stub.body', { story: def.story })}</Body>
          <Body tone="small">{t('stub.api', { apis: def.api.join(' · ') })}</Body>
        </>
      )}
    </Screen>
  );
}
