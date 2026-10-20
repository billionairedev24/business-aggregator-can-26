import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { AppErrorBoundary, FailureScreen, I18nProvider, RETRY_BACKOFF_SECONDS, resetFailureRetries, type Locale } from './index';

const page = (ui: React.ReactNode, locale: Locale = 'en') => render(<I18nProvider initial={locale}><main>{ui}</main></I18nProvider>);
afterEach(() => { vi.useRealTimers(); resetFailureRetries(); });

describe('FailureScreen', () => {
  it('an error: plain words, Try again, Go to home and support — never the raw message', async () => {
    const onRetry = vi.fn();
    page(<FailureScreen kind="error" onRetry={onRetry} supportHref="mailto:help@example.ca" />);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Something went wrong on our side.');
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledOnce();
    expect(screen.getByRole('link', { name: 'Go to home' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('link', { name: 'Contact support' })).toHaveAttribute('href', 'mailto:help@example.ca');
    await expectNoAxeViolations(document.body);
  });

  it('unreachable: tries again on its own with a backoff, then leaves it to the visitor', async () => {
    vi.useFakeTimers();
    const onRetry = vi.fn();
    const { rerender } = page(<FailureScreen kind="unreachable" autoRetry onRetry={onRetry} />);
    expect(screen.getByRole('status')).toHaveTextContent('Trying again in 2 seconds…');
    for (const [i, seconds] of RETRY_BACKOFF_SECONDS.entries()) {
      await act(async () => { vi.advanceTimersByTime(seconds * 1000); });
      expect(onRetry).toHaveBeenCalledTimes(i + 1);
      // each failed try renders the screen again (the route's error boundary)
      rerender(<I18nProvider initial="en"><main><FailureScreen key={i} kind="unreachable" autoRetry onRetry={onRetry} /></main></I18nProvider>);
    }
    expect(screen.getByRole('status')).toHaveTextContent('Still not answering. Try again in a few minutes.');
  });

  it('in French', async () => {
    page(<FailureScreen kind="unreachable" autoRetry onRetry={() => {}} />, 'fr');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Connexion impossible pour le moment.');
    expect(screen.getByRole('status')).toHaveTextContent('Nouvel essai dans 2 secondes…');
    expect(screen.getByRole('button', { name: 'Réessayer' })).toBeInTheDocument();
    await expectNoAxeViolations(document.body);
  });

  it('not found and forbidden lead home; updated reloads', () => {
    const { rerender } = page(<FailureScreen kind="notFound" />);
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.getByRole('link', { name: 'Go to home' })).toHaveClass('btn-primary');
    rerender(<I18nProvider initial="en"><main><FailureScreen kind="updated" /></main></I18nProvider>);
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument();
  });

  it('AppErrorBoundary: a render error shows the branded screen instead of a blank page', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const Boom = () => { throw new TypeError('Failed to fetch dynamically imported module: /assets/x.js'); };
    page(<AppErrorBoundary kindOf={() => 'updated'}><Boom /></AppErrorBoundary>);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('A new version is available.');
    expect(screen.queryByText(/dynamically imported/)).toBeNull();
    spy.mockRestore();
  });
});
