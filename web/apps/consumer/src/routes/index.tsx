import { createFileRoute, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { SearchBar } from '@northline/ui';
export const Route = createFileRoute('/')({ component: Home });
function Home() {
  const nav = useNavigate(); const [q, setQ] = useState('');
  return (
    <section style={{ marginTop: 20, background: 'var(--color-accent)', color: 'var(--color-on-accent)', borderRadius: 'var(--radius-lg)', padding: 56, display: 'flex', flexDirection: 'column', gap: 20 }}>
      <h1 style={{ fontSize: 60, margin: 0, color: 'inherit', maxWidth: '18ch' }}>What do you need today?</h1>
      <div style={{ maxWidth: 820 }}><SearchBar value={q} onChange={setQ} onSubmit={() => nav({ to: '/search', search: { q, scope: 'all' } })} /></div>
      {/* scope chips → /services, /shop, /food landing pages (never raw results) — see design/06 Consumer Web */}
    </section>
  );
}
