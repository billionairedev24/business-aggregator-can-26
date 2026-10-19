import Svg, { Path } from 'react-native-svg';

import { colors } from '../theme';
import { ICON_PATHS, type IconName } from './icons.generated';

export type { IconName } from './icons.generated';
export { ICON_PATHS } from './icons.generated';

/**
 * A Phosphor icon (redesign spec § 1.2): the regular weight, `filled` for the active or selected state (the tab
 * you're on, a saved item). Icons are decorative — the control around them carries the name — so they're hidden
 * from VoiceOver and TalkBack.
 */
export function Icon({ name, size = 24, color = colors.text, filled, testID }: { name: IconName; size?: number; color?: string; filled?: boolean; testID?: string }) {
  const paths = ICON_PATHS[name][filled ? 'fill' : 'regular'];
  return (
    <Svg
      width={size}
      height={size}
      viewBox="0 0 256 256"
      fill={color}
      accessible={false}
      importantForAccessibility="no-hide-descendants"
      accessibilityElementsHidden
      testID={testID ?? `icon-${name}${filled ? '-fill' : ''}`}
    >
      {paths.map((d, i) => (
        <Path key={i} d={d} />
      ))}
    </Svg>
  );
}
