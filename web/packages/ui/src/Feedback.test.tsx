import { describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nProvider, formatDateRange, formatPrice, formatTimeRange, formatUnitPrice, formatWhen, unitCents, type Locale } from './i18n';
import { Pagination, pageItems, ToastProvider, useToast } from './Feedback';
import { ChoiceTiles, FilterChip, UnderlineTabs } from './Choice';

const page = (ui: React.ReactNode, locale: Locale = 'en') => render(<I18nProvider initial={locale}>{ui}</I18nProvider>);

describe('formatters (E-15: one price format, no raw ISO dates)', () => {
  it('prices: $8.50 / 8,50 $, unit prices per 100 g or each', () => {
    expect(formatPrice(850, 'en')).toBe('$8.50');
    expect(formatPrice(850, 'fr')).toBe('8,50 $');
    expect(unitCents(850, 900, '100g')).toBe(94);
    expect(formatUnitPrice(94, '100g', 'en')).toBe('$0.94/100 g');
    expect(formatUnitPrice(375, 'each', 'fr')).toBe('3,75 $ chacun');
  });

  it('time ranges: one day reads as hours, across midnight names both days, never ISO', () => {
    const zone = 'UTC';
    expect(formatTimeRange('2026-10-19T18:00:00Z', '2026-10-19T21:00:00Z', 'en', zone)).toBe('6–9 p.m.');
    expect(formatTimeRange('2026-10-19T18:00:00Z', '2026-10-19T21:00:00Z', 'fr', zone)).toBe('18 h – 21 h');
    const across = formatTimeRange('2026-10-19T21:36:00Z', '2026-10-20T00:36:00Z', 'en', zone);
    expect(across).toMatch(/Oct 19.*9:36.*Oct 20.*12:36/);
    expect(across).not.toMatch(/2026-10/);
  });

  it('date ranges and single dates from calendar dates or instants', () => {
    expect(formatDateRange('2026-10-19', '2026-10-22', 'en')).toBe('Oct 19–22');
    expect(formatDateRange('2026-10-30', '2026-11-02', 'fr')).not.toMatch(/2026-/);
    expect(formatWhen('2026-10-19', 'en')).toBe('Oct 19');
    expect(formatWhen('2026-10-19T15:00:00Z', 'en', 'date', 'UTC')).toBe('Oct 19');
  });
});

describe('controls (E-15: every choice has a visible selected state)', () => {
  it('a filter chip toggles with aria-pressed; an applied one removes itself, named for it', async () => {
    const onToggle = vi.fn(), onRemove = vi.fn();
    page(<><FilterChip label="Vegan" applied={false} onToggle={onToggle} /><FilterChip label="Tonight" applied onRemove={onRemove} /></>, 'fr');
    expect(screen.getByRole('button', { name: 'Vegan' })).toHaveAttribute('aria-pressed', 'false');
    await userEvent.click(screen.getByRole('button', { name: 'Vegan' }));
    expect(onToggle).toHaveBeenCalled();
    const applied = screen.getByRole('button', { name: 'Retirer le filtre : Tonight' });
    expect(applied).toHaveAttribute('data-state', 'on');
    await userEvent.click(applied);
    expect(onRemove).toHaveBeenCalled();
  });

  it('one-choice tiles are a radiogroup: the chosen one is checked, the arrows choose', async () => {
    const onChange = vi.fn();
    page(<ChoiceTiles aria-label="Time" value="0930" onChange={onChange} options={[{ value: '0930', label: '9:30 a.m.' }, { value: '1100', label: '11:00 a.m.' }]} />);
    expect(screen.getByRole('radio', { name: '9:30 a.m.' })).toHaveAttribute('aria-checked', 'true');
    screen.getByRole('radio', { name: '9:30 a.m.' }).focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(onChange).toHaveBeenCalledWith('1100');
  });

  it('underline tabs carry their counts', () => {
    page(<UnderlineTabs aria-label="Orders" value="all" onChange={() => {}} options={[{ value: 'all', label: 'All', count: 38 }, { value: 'issues', label: 'Issues', count: 1, tone: 'danger' }]} />);
    expect(screen.getByRole('tab', { name: 'All 38', selected: true })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Issues 1' }).querySelector('[data-tone="danger"]')).toHaveTextContent('1');
  });
});

describe('feedback', () => {
  it('pagination: first, last, the current page and its neighbours; the current one marked', async () => {
    expect(pageItems(5, 12)).toEqual([1, 'gap', 4, 5, 6, 'gap', 12]);
    expect(pageItems(1, 4)).toEqual([1, 2, 3, 4]);
    const onChange = vi.fn();
    page(<Pagination page={2} pages={4} onChange={onChange} range={{ from: 25, to: 48, total: 86 }} />, 'fr');
    expect(screen.getByRole('button', { name: 'Page 2, page actuelle' })).toHaveAttribute('aria-current', 'page');
    await userEvent.click(screen.getByRole('button', { name: 'Suivante' }));
    expect(onChange).toHaveBeenCalledWith(3);
    expect(screen.getByText('25 à 48 sur 86')).toBeInTheDocument();
  });

  it('toasts are announced and leave on their own', () => {
    vi.useFakeTimers();
    function Saver() { const { show } = useToast(); return <button type="button" onClick={() => show({ tone: 'success', children: 'Saved' })}>Save</button>; }
    page(<ToastProvider><Saver /></ToastProvider>);
    act(() => screen.getByRole('button', { name: 'Save' }).click());
    expect(screen.getByRole('status')).toHaveTextContent('Saved');
    act(() => { vi.advanceTimersByTime(5_100); });
    expect(screen.getByRole('status')).not.toHaveTextContent('Saved');
    vi.useRealTimers();
  });
});
