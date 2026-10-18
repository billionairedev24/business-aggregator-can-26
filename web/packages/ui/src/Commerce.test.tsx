import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nProvider, type Locale } from './i18n';
import { Price, ProductCard, QuantityStepper, ServiceCard, Stars } from './Commerce';

const page = (ui: React.ReactNode, locale: Locale = 'en') => render(<I18nProvider initial={locale}>{ui}</I18nProvider>);

describe('commerce kit', () => {
  it('prices: superscript sign and cents, the plain amount for screen readers, the earlier price struck through', () => {
    const { container } = page(<Price cents={4504} compareAtCents={5200} />);
    expect(container.querySelector('.nl-price-whole')).toHaveTextContent('45');
    expect(container.querySelectorAll('.nl-price-sup')[1]).toHaveTextContent('04');
    expect(screen.getByText('$45.04')).toHaveClass('nl-sr-only');
    expect(screen.getByText('Was $52.00')).toBeInTheDocument();
  });

  it('prices in French put the sign after the figure', () => {
    const { container } = page(<Price cents={128050} />, 'fr');
    expect(container.querySelector('.nl-price-whole')?.textContent).toMatch(/^1\s280$/);
    expect(container.querySelector('.nl-price-amount')?.lastElementChild).toHaveTextContent('$');
  });

  it('stars: rounded to the half, announced, with the review count', () => {
    const { container } = page(<Stars rating={4.26} reviewCount={1240} />, 'fr');
    expect(screen.getByRole('img', { name: '4,3 sur 5 étoiles' })).toBeInTheDocument();
    const fills = [...container.querySelectorAll<HTMLElement>('.nl-star-fill')].map(f => f.style.width);
    expect(fills).toEqual(['100%', '100%', '100%', '100%', '50%']);
    expect(screen.getByText('1 240 avis')).toBeInTheDocument();
  });

  it('the stepper turns into a bin at 1 and stops at the stock', async () => {
    const onChange = vi.fn();
    page(<QuantityStepper value={1} max={1} onChange={onChange} name="Baguette" />);
    expect(screen.getByRole('button', { name: 'One more Baguette' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Remove Baguette' }));
    expect(onChange).toHaveBeenCalledWith(0);
  });

  it('a product card: one link, add to cart, then the stepper', async () => {
    const onAdd = vi.fn();
    const { rerender } = page(<ProductCard href="/products/p1" name="Sourdough" priceCents={750} onAdd={onAdd} lowStock={2} eta="Tonight" />);
    const card = screen.getByRole('article', { name: 'Sourdough' });
    expect(within(card).getAllByRole('link')).toHaveLength(1);
    expect(within(card).getByRole('heading', { level: 3, name: 'Sourdough' })).toBeInTheDocument();
    expect(within(card).getByText('Only 2 left')).toBeInTheDocument();
    await userEvent.click(within(card).getByRole('button', { name: 'Add to cart: Sourdough' }));
    expect(onAdd).toHaveBeenCalled();
    rerender(<I18nProvider initial="en"><ProductCard href="/products/p1" name="Sourdough" priceCents={750} onAdd={onAdd} onQuantity={() => {}} inCart={3} /></I18nProvider>);
    expect(screen.getByRole('group', { name: 'Quantity of Sourdough' })).toHaveTextContent('3 in cart');
  });

  it('a service card links to the provider, or is a pressable choice', () => {
    page(<>
      <ServiceCard href="/providers/pw" kind="Mobile mechanic" name="Prairie Wrench" rating={4.9} reviewCount={312} priceCents={8900} place="2.4 km" verified />
      <ServiceCard onSelect={() => {}} selected name="Brake pads" priceCents={18900} fromPrice={false} />
    </>);
    const link = screen.getByRole('link', { name: /Prairie Wrench/ });
    expect(link).toHaveAttribute('href', '/providers/pw');
    expect(link).toHaveTextContent('from');
    expect(link).toHaveTextContent('Verified');
    expect(screen.getByRole('button', { name: /Brake pads/ })).toHaveAttribute('aria-pressed', 'true');
  });
});
