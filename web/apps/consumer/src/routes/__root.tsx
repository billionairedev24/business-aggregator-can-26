import { createRootRouteWithContext, HeadContent, Outlet, Scripts, useNavigate, useRouterState } from '@tanstack/react-router';
import { QueryClientProvider, type QueryClient, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { SiteHeader, useGeolocation } from '@northline/ui';
import tokensCss from '@northline/tokens/tokens.css?url';
import { meQuery } from '../api/me';

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  head: () => ({ meta: [{ charSet: 'utf-8' }, { name: 'viewport', content: 'width=device-width, initial-scale=1' }, { title: 'Northline' }], links: [{ rel: 'stylesheet', href: tokensCss }] }),
  component: Root,
});

function Root() {
  const { queryClient } = Route.useRouteContext();
  return (<html lang="en"><head><HeadContent /></head><body><QueryClientProvider client={queryClient}><Shell /></QueryClientProvider><Scripts /></body></html>);
}

function Shell() {
  const nav = useNavigate();
  const path = useRouterState({ select: s => s.location.pathname });
  const { data: me } = useQuery(meQuery);
  const geo = useGeolocation(me?.defaultAddress?.label);
  const [q, setQ] = useState('');
  return (<>
    <SiteHeader geo={geo} onLocation={() => nav({ to: '/location' })} showSearch={path !== '/'} query={q} onQuery={setQ}
      onSearch={() => nav({ to: '/search', search: { q, scope: 'all' } })} cartCount={me?.cartCount ?? 0}
      user={me ? { name: me.name, email: me.email, initials: me.initials } : undefined} activeOrders={me?.activeOrders}
      onNavigate={to => nav({ to })} onSignIn={() => (location.href = '/bff/login')} onCreateAccount={() => (location.href = '/bff/login?prompt=create')}
      onNotYou={() => (location.href = '/bff/logout?reauth=1')} onSignOut={() => (location.href = '/bff/logout')} />
    <main style={{ maxWidth: 1280, margin: '0 auto', padding: '0 32px 80px' }}><Outlet /></main>
  </>);
}
