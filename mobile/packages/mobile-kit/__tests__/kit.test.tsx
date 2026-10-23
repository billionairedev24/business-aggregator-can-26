import { fireEvent, render, screen } from '@testing-library/react-native';
import { StyleSheet, Text } from 'react-native';

import theme from '@northline/tokens/theme.json';
import { MIN_TARGET, radius, semantic } from '../src/theme';
import {
  BottomTabBar,
  Button,
  CountBadge,
  FilterChip,
  KitGallery,
  KitProvider,
  OptionCard,
  Price,
  Rating,
  SearchBar,
  Segmented,
  StatusTag,
  Switch,
  UnderlineTabs,
  formatDateRange,
  formatPrice,
  formatTimeRange,
  formatUnitPrice,
  kitEn,
  kitFrCA,
  unitCents,
} from '../src/ui/kit';

const flat = (s: unknown) => StyleSheet.flatten(typeof s === 'function' ? (s as (p: { pressed: boolean }) => unknown)({ pressed: false }) : s) as Record<string, unknown>;

describe('the one price format and the date ranges (the web’s @northline/ui rules)', () => {
  it('formats prices as $8.50 / 8,50 $ with the unit price as meta', () => {
    expect(formatPrice(850, 'en')).toBe('$8.50');
    expect(formatPrice(850, 'fr-CA')).toBe('8,50 $');
    expect(formatPrice(123456, 'en')).toBe('$1,234.56');
    expect(formatPrice(123456, 'fr-CA')).toBe('1 234,56 $');
    expect(unitCents(850, 900, '100g')).toBe(94);
    expect(formatUnitPrice(94, '100g', 'en')).toBe('$0.94/100 g');
    expect(formatUnitPrice(375, 'each', 'fr-CA')).toBe('3,75 $ chacun');
  });

  it('formats a delivery window and a span of days, never an ISO string', () => {
    expect(formatTimeRange('2026-10-23T18:00:00Z', '2026-10-23T21:00:00Z', 'en', 'UTC')).toMatch(/^6\s?–\s?9 p\.m\.$/);
    expect(formatTimeRange('2026-10-23T18:00:00Z', '2026-10-23T21:00:00Z', 'fr-CA', 'UTC')).toMatch(/18 h/);
    expect(formatTimeRange('2026-10-23T22:00:00Z', '2026-10-24T01:00:00Z', 'en', 'UTC')).toMatch(/Oct 23.*Oct 24/);
    expect(formatDateRange('2026-10-19', '2026-10-22', 'en')).toMatch(/^Oct 19\s?–\s?22$/);
    expect(formatDateRange('2026-10-30', '2026-11-02', 'en')).toMatch(/Oct 30.*Nov 2/);
    expect(formatDateRange('2026-10-19', '2026-10-22', 'fr-CA')).toMatch(/19.*22 oct/);
    for (const s of [formatTimeRange('2026-10-23T18:00:00Z', '2026-10-24T01:00:00Z', 'en'), formatDateRange('2026-10-19', '2026-10-22', 'en')]) expect(s).not.toMatch(/\d{4}-\d{2}/);
  });
});

describe('the kit’s words', () => {
  it('has every English key in fr-CA, none empty, with the same parameters', () => {
    const params = (s: string) => [...new Set([...s.replace(/(?:\b(?:one|other)|=\d+)\s*\{/g, '(').matchAll(/\{(\w+)(?:,|\})/g)].map((m) => m[1]))].sort();
    expect(Object.keys(kitFrCA).sort()).toEqual(Object.keys(kitEn).sort());
    for (const [k, en] of Object.entries(kitEn)) {
      const fr = kitFrCA[k as keyof typeof kitEn];
      expect(fr.trim()).not.toBe('');
      expect({ k, p: params(fr) }).toEqual({ k, p: params(en) });
    }
  });

  it('names no place, province or time zone (region-neutral)', () => {
    const all = JSON.stringify([kitEn, kitFrCA]);
    expect(all).not.toMatch(/Alberta|Calgary|Edmonton|Kensington|America\//);
  });
});

describe('shapes and states (Plainsight)', () => {
  it('draws buttons as 3 pt boxes in the four roles, never pills, each a 44 pt target', () => {
    const roles = { primary: 'action-primary', secondary: 'action-neutral', destructive: 'action-danger', tertiary: null } as const;
    for (const [variant, bg] of Object.entries(roles)) {
      render(<Button label={variant} variant={variant as keyof typeof roles} onPress={() => undefined} />);
      const b = screen.getByRole('button', { name: variant });
      const s = flat(b.props.style);
      expect(s.borderRadius).toBe(radius.control);
      expect(radius.control).toBe(3);
      if (bg) expect(s.backgroundColor).toBe(semantic[bg]);
      expect(Number(s.minHeight)).toBeGreaterThanOrEqual(MIN_TARGET);
    }
    render(<Button label="small" size="sm" onPress={() => undefined} />);
    const sm = screen.getByRole('button', { name: 'small' });
    const slop = sm.props.hitSlop as { top: number; bottom: number };
    expect(Number(flat(sm.props.style).minHeight) + slop.top + slop.bottom).toBeGreaterThanOrEqual(MIN_TARGET);
  });

  it('keeps round what is a circle: the cart count and the switch use radius.round, not pill', () => {
    expect(radius.round).toBeGreaterThan(100);
    expect(radius.pill).toBe(theme.radius.pill);
    const { toJSON } = render(<CountBadge count={4} testID="count" />);
    expect(flat(screen.getByTestId('count').props.style).borderRadius).toBe(radius.round);
    expect(flat(screen.getByTestId('count').props.style).backgroundColor).toBe(semantic['cart-badge']);
    expect(toJSON()).toBeTruthy();
  });

  it('shows the selected choice: tabs, segments, option cards, switches', () => {
    const onChange = jest.fn();
    render(
      <>
        <UnderlineTabs label="Orders" value="pack" onChange={onChange} options={[{ value: 'pack', label: 'To pack', count: 3 }, { value: 'done', label: 'Delivered', count: 32 }]} />
        <Segmented label="Tip" value="3" onChange={onChange} options={[{ value: '2', label: '$2' }, { value: '3', label: '$3' }]} />
        <OptionCard title="Tonight" selected onPress={onChange} />
        <Switch label="Text me" value onChange={onChange} />
      </>,
    );
    expect(screen.getByRole('tab', { name: 'To pack, 3' }).props.accessibilityState.selected).toBe(true);
    expect(screen.getByRole('tab', { name: 'Delivered, 32' }).props.accessibilityState.selected).toBe(false);
    const on = screen.getByRole('radio', { name: '$3' });
    expect(on.props.accessibilityState.checked).toBe(true);
    expect(flat(on.props.style).backgroundColor).toBe(semantic.selected);
    expect(flat(screen.getByRole('radio', { name: '$2' }).props.style).backgroundColor).not.toBe(semantic.selected);
    expect(flat(screen.getByRole('radio', { name: 'Tonight' }).props.style).borderColor).toBe(semantic.selected);
    expect(screen.getByRole('switch', { name: 'Text me' }).props.accessibilityState.checked).toBe(true);
    fireEvent.press(screen.getByRole('tab', { name: 'Delivered, 32' }));
    expect(onChange).toHaveBeenCalledWith('done');
  });

  it('draws filter chips square; an applied one removes itself and says so (en, fr-CA)', () => {
    const remove = jest.fn();
    render(<FilterChip label="Tonight" onRemove={remove} />);
    const chip = screen.getByRole('button', { name: 'Remove filter: Tonight' });
    expect(flat(chip.props.style).borderRadius).toBe(radius.control);
    expect(flat(chip.props.style).borderColor).toBe(semantic.selected);
    fireEvent.press(chip);
    expect(remove).toHaveBeenCalled();
    screen.unmount();
    render(
      <KitProvider locale="fr-CA">
        <FilterChip label="Végane" onRemove={remove} />
      </KitProvider>,
    );
    expect(screen.getByRole('button', { name: 'Retirer le filtre : Végane' })).toBeTruthy();
  });

  it('reads prices, ratings and status in words', () => {
    render(
      <KitProvider locale="en">
        <Price cents={850} unit={{ cents: 94, per: '100g' }} testID="price" />
        <Rating value={4.8} count={312} testID="rating" />
        <StatusTag label="Delivered" tone="success" testID="tag" />
      </KitProvider>,
    );
    expect(screen.getByTestId('price').props.accessibilityLabel).toBe('$8.50, $0.94/100 g');
    expect(screen.getByTestId('rating').props.accessibilityLabel).toBe('4.8 out of 5 stars, 312 reviews');
    expect(flat(screen.getByTestId('tag').props.style).backgroundColor).toBe(semantic['status-success-bg']);
  });

  it('scopes the search to a department through a bottom sheet', () => {
    const onScope = jest.fn();
    render(<SearchBar placeholder="Search" value="" onChangeText={() => undefined} scopes={[{ value: 'shop', label: 'Shop' }, { value: 'food', label: 'Food' }]} scope="shop" onScope={onScope} testID="s" />);
    fireEvent.press(screen.getByRole('button', { name: 'Search in: Shop' }));
    fireEvent.press(screen.getByRole('radio', { name: 'Food' }));
    expect(onScope).toHaveBeenCalledWith('food');
  });

  it('marks the current tab in ink and reads the cart count', () => {
    const onSelect = jest.fn();
    render(<BottomTabBar current="home" onSelect={onSelect} items={[{ key: 'home', label: 'Home', icon: 'house' }, { key: 'cart', label: 'Cart', icon: 'shopping-cart', badge: 3 }]} />);
    expect(screen.getByRole('tab', { name: 'Home' }).props.accessibilityState.selected).toBe(true);
    fireEvent.press(screen.getByRole('tab', { name: 'Cart, 3 items' }));
    expect(onSelect).toHaveBeenCalledWith('cart');
  });
});

describe('the gallery (dev builds)', () => {
  it('shows every section and switches language', () => {
    render(<KitGallery initialLocale="en" />);
    for (const h of ['Buttons', 'Underline tabs', 'Segmented control', 'Filter chips', 'One-choice tiles', 'Commerce', 'Status tags', 'Formats', 'Search', 'Feedback', 'Navigation']) expect(screen.getByRole('header', { name: h })).toBeTruthy();
    expect(screen.getAllByText('$8.50').length).toBeGreaterThan(0);
    fireEvent.press(screen.getByRole('radio', { name: 'Français' }));
    expect(screen.getAllByText('Trousse de composants').length).toBeGreaterThan(0);
    expect(screen.getAllByText('8,50 $').length).toBeGreaterThan(0);
    expect(screen.getByTestId('photo-fallback')).toBeTruthy();
    expect(screen.queryByText(/\{\w+\}/)).toBeNull();
    expect(Text).toBeTruthy();
  });
});
