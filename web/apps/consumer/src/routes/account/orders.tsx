import { createFileRoute } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useReactTable, getCoreRowModel, flexRender, createColumnHelper } from '@tanstack/react-table';
type Order = { id: string; merchant: string; placedAt: string; status: string; totalCents: number };
const col = createColumnHelper<Order>();
const columns = [col.accessor('merchant', { header: 'From' }), col.accessor('placedAt', { header: 'Placed' }), col.accessor('status', { header: 'Status' }), col.accessor('totalCents', { header: 'Total', cell: c => (c.getValue() / 100).toLocaleString('en-CA', { style: 'currency', currency: 'CAD' }) })];
export const Route = createFileRoute('/account/orders')({ component: Orders });
function Orders() {
  const { data = [] } = useQuery({ queryKey: ['orders'], queryFn: () => fetch('/api/v1/me/orders').then(r => r.json()) as Promise<Order[]> });
  const t = useReactTable({ data, columns, getCoreRowModel: getCoreRowModel() });
  return (<><h1>Orders &amp; bookings</h1><table className="table"><thead>{t.getHeaderGroups().map(g => <tr key={g.id}>{g.headers.map(h => <th key={h.id}>{flexRender(h.column.columnDef.header, h.getContext())}</th>)}</tr>)}</thead><tbody>{t.getRowModel().rows.map(r => <tr key={r.id}>{r.getVisibleCells().map(c => <td key={c.id}>{flexRender(c.column.columnDef.cell, c.getContext())}</td>)}</tr>)}</tbody></table></>);
}
