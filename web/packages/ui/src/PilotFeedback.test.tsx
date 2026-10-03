import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expectNoAxeViolations } from '@northline/a11y/vitest';
import { I18nProvider, PilotFeedback, describePlatform, feedbackContext, screenshotProblem, type Locale } from './index';

const page = (onSubmit = vi.fn(async () => ({ reference: 'UAT-1001' })), locale: Locale = 'en') =>
  ({ onSubmit, ...render(<I18nProvider initial={locale}><main><h1>Test</h1><PilotFeedback onSubmit={onSubmit} placement="inline" /></main></I18nProvider>) });

describe('PilotFeedback (S-121)', () => {
  it('sends the category, severity, text and attached screenshot, then shows the reference', async () => {
    const { onSubmit } = page();
    await userEvent.click(screen.getByRole('button', { name: 'Send feedback' }));
    await userEvent.click(screen.getByLabelText('Something’s confusing'));
    await userEvent.selectOptions(screen.getByLabelText('How much did it get in your way?'), 'blocker');
    await userEvent.type(screen.getByLabelText('What happened?'), '  The deposit line is missing.  ');
    const file = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'shot.png', { type: 'image/png' });
    await userEvent.upload(document.querySelector('input[type=file]') as HTMLInputElement, file);
    expect(screen.getByText(/Attached: shot.png/)).toBeInTheDocument();
    await expectNoAxeViolations(document.body);
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(onSubmit).toHaveBeenCalledWith({ category: 'confusing', severity: 'blocker', body: 'The deposit line is missing.', screenshot: file });
    expect(await screen.findByRole('status')).toHaveTextContent('UAT-1001');
  });

  it('refuses an empty note and a file that is not a PNG or JPEG, in French too', async () => {
    const { onSubmit } = page(undefined, 'fr');
    await userEvent.click(screen.getByRole('button', { name: 'Envoyer un commentaire' }));
    await userEvent.upload(document.querySelector('input[type=file]') as HTMLInputElement, new File(['GIF8'], 'a.gif', { type: 'image/gif' }), { applyAccept: false });
    expect(screen.getByRole('alert')).toHaveTextContent('Joignez la capture d’écran en image PNG ou JPEG.');
    await userEvent.click(screen.getByRole('button', { name: 'Envoyer' }));
    expect(screen.getByText('Dites-nous ce qui s’est passé, en 1 à 4 000 caractères.')).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
    await expectNoAxeViolations(document.body);
  });

  it('shows what the server answered when sending fails', async () => {
    page(vi.fn(async () => { throw new Error('Feedback here is for pilot participants.'); }));
    await userEvent.click(screen.getByRole('button', { name: 'Send feedback' }));
    await userEvent.type(screen.getByLabelText('What happened?'), 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Feedback here is for pilot participants.');
  });
});

describe('feedback context', () => {
  it('describes the browser and OS only, and checks screenshots', () => {
    expect(describePlatform('Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:131.0) Gecko/20100101 Firefox/131.0')).toBe('Firefox 131 · macOS');
    expect(describePlatform('Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/129.0 Safari/537.36 Edg/129.0')).toBe('Edge 129 · Windows');
    expect(describePlatform('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0) AppleWebKit/605 Version/18.0 Mobile/15E148 Safari/604.1')).toBe('Safari 18 · iOS');
    expect(screenshotProblem({ type: 'image/jpeg', size: 10 })).toBeUndefined();
    expect(screenshotProblem({ type: 'image/webp', size: 10 })).toBe('type');
    expect(screenshotProblem({ type: 'image/png', size: 5 * 1024 * 1024 + 1 })).toBe('size');
  });

  it('sends the path without its query string or fragment', () => {
    window.history.pushState({}, '', '/invite/abc?token=secret#x');
    const ctx = feedbackContext('2026.10.1', 'fr');
    expect(ctx.route).toBe('/invite/abc');
    expect(ctx.locale).toBe('fr-CA');
    expect(ctx.appVersion).toBe('2026.10.1');
    expect(feedbackContext('not a version!', 'en').appVersion).toBe('dev');
  });
});
