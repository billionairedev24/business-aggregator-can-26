import { render, screen, fireEvent } from '@testing-library/react-native';
import { Pressable, Text } from 'react-native';

import theme from '@northline/tokens/theme.json';
import { createI18n, formatMessage, resolveLocale } from '../src/i18n';
import { colors, fonts, radius, semantic, space, text } from '../src/theme';
import { mixOklch, toOklch } from '../src/theme/oklch';

describe('theme from @northline/tokens', () => {
  it('uses the brand palette as the web resolves it (generated/theme.json)', () => {
    expect(colors.actionPrimary).toBe(theme.color['action-primary']);
    expect(colors.accent).toBe(theme.color['action-primary']);
    expect(colors.highlight).toBe(theme.color['action-accent']);
    expect(colors.bg).toBe(theme.color.canvas);
    expect(colors.text).toBe(theme.color.text);
    expect(semantic['text-muted']).toBe(theme.color['text-muted']);
  });

  it('derives in OKLCH like the web (mixing with white keeps the hue)', () => {
    expect(mixOklch('#1E4D36', '#FFFFFF', 0)).toBe('#1E4D36');
    expect(mixOklch('#1E4D36', '#FFFFFF', 1)).toBe('#FFFFFF');
    const l = (hex: string) => toOklch(hex).l;
    expect(l(colors.accent100)).toBeGreaterThan(l(colors.accent200));
    expect(l(colors.accent200)).toBeGreaterThan(l(colors.accent300));
    expect(l(colors.accent300)).toBeGreaterThan(l(colors.accent));
    expect(l(colors.accent)).toBeGreaterThan(l(colors.accent600));
    expect(Math.abs(toOklch(colors.accent200).h - toOklch(colors.accent).h)).toBeLessThan(6);
    expect(Object.values(colors).every((c) => /^(#[0-9A-F]{6}|rgba\(.*\))$/.test(c))).toBe(true);
  });

  it('meets WCAG AA for body text and buttons', () => {
    const lum = (hex: string) => {
      const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
      return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
    };
    const contrast = (a: string, b: string) => {
      const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
      return (x! + 0.05) / (y! + 0.05);
    };
    expect(contrast(colors.text, colors.bg)).toBeGreaterThan(7);
    expect(contrast(colors.onAccent, colors.accent)).toBeGreaterThan(4.5);
    expect(contrast(colors.neutral700, colors.bg)).toBeGreaterThan(4.5);
    expect(contrast(colors.accent2_700, colors.surface)).toBeGreaterThan(4.5);
    expect(contrast(colors.onHighlight, colors.highlight)).toBeGreaterThan(4.5);
  });

  it('takes radii, spacing and type from the tokens', () => {
    expect(radius).toEqual({ xs: 4, sm: 6, md: 8, lg: 12, pill: 999 });
    expect(space[4]).toBe(16);
    expect(text.base).toBe(15);
    expect(fonts.heading).toBe('Newsreader_500Medium');
  });
});

describe('i18n', () => {
  it('picks fr-CA for any French device language', () => {
    expect(resolveLocale(['fr-FR'])).toBe('fr-CA');
    expect(resolveLocale(['en-CA', 'fr-CA'])).toBe('fr-CA');
    expect(resolveLocale(['de-DE'])).toBe('en');
  });

  it('formats parameters and plurals per language', () => {
    const msg = '{n, plural, =0 {No stops} one {# stop} other {# stops}} in {city}';
    expect(formatMessage('en', msg, { n: 0, city: 'X' })).toBe('No stops in X');
    expect(formatMessage('en', msg, { n: 1, city: 'X' })).toBe('1 stop in X');
    expect(formatMessage('en', msg, { n: 3, city: 'X' })).toBe('3 stops in X');
    const fr = '{n, plural, one {# arrêt} other {# arrêts}}';
    expect(formatMessage('fr-CA', fr, { n: 1 })).toBe('1 arrêt');
    expect(formatMessage('fr-CA', fr, { n: 2 })).toBe('2 arrêts');
    expect(formatMessage('en', 'Hello {missing}')).toBe('Hello {missing}');
  });

  it('switches language at runtime and formats times in the market zone', () => {
    const { I18nProvider, useI18n } = createI18n({ en: { hello: 'Hello {name}' }, 'fr-CA': { hello: 'Bonjour {name}' } });
    function Probe() {
      const i18n = useI18n();
      return (
        <Pressable accessibilityRole="button" onPress={() => i18n.setLocale('fr-CA')}>
          <Text>{i18n.t('hello', { name: 'Kai' })}</Text>
          <Text>{i18n.time('2026-10-01T01:10:00Z', 'America/Toronto')}</Text>
        </Pressable>
      );
    }
    render(
      <I18nProvider initial="en">
        <Probe />
      </I18nProvider>,
    );
    expect(screen.getByText('Hello Kai')).toBeTruthy();
    expect(screen.getByText(/9:10/)).toBeTruthy();
    fireEvent.press(screen.getByRole('button'));
    expect(screen.getByText('Bonjour Kai')).toBeTruthy();
    expect(screen.getByText(/21 h 10/)).toBeTruthy();
  });
});
