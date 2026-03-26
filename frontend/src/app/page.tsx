'use client';

import { useQuery } from '@tanstack/react-query';
import { FileText, Send, Inbox, AlertCircle } from 'lucide-react';
import Link from 'next/link';
import { useUser } from '@/lib/user-context';
import { invoicesApi } from '@/lib/api';
import { formatPLN, formatDate } from '@/lib/utils';
import { StatusBadge } from '@/components/ui/StatusBadge';
import UserSetup from '@/components/forms/UserSetup';

export default function DashboardPage() {
  const { user, userId } = useUser();

  const { data: issued } = useQuery({
    queryKey: ['invoices', userId, 'ISSUED'],
    queryFn: () => invoicesApi.list(userId, { direction: 'ISSUED', size: 5 }),
    enabled: !!userId,
  });

  const { data: received } = useQuery({
    queryKey: ['invoices', userId, 'RECEIVED'],
    queryFn: () => invoicesApi.list(userId, { direction: 'RECEIVED', size: 5 }),
    enabled: !!userId,
  });

  if (!user) return <UserSetup />;

  const stats = [
    {
      label: 'Wystawione',
      value: issued?.totalElements ?? '—',
      icon: Send,
      color: 'text-blue-600 bg-blue-50',
      href: '/faktury?direction=ISSUED',
    },
    {
      label: 'Odebrane',
      value: received?.totalElements ?? '—',
      icon: Inbox,
      color: 'text-purple-600 bg-purple-50',
      href: '/faktury?direction=RECEIVED',
    },
    {
      label: 'Błędy',
      value: issued?.content.filter(i => i.status === 'FAILED').length ?? 0,
      icon: AlertCircle,
      color: 'text-red-600 bg-red-50',
      href: '/faktury',
    },
  ];

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">{user.companyName}</h1>
          <p className="text-sm text-gray-500 mt-0.5">NIP: {user.nip} · Środowisko testowe KSeF</p>
        </div>
        <Link href="/faktury/nowa" className="btn-primary">
          <FileText size={16} />
          Nowa faktura
        </Link>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-3 gap-4">
        {stats.map(({ label, value, icon: Icon, color, href }) => (
          <Link key={label} href={href} className="card p-5 hover:shadow-md transition-shadow">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-gray-500">{label}</p>
                <p className="text-3xl font-semibold mt-1">{value}</p>
              </div>
              <div className={`p-3 rounded-xl ${color}`}>
                <Icon size={22} />
              </div>
            </div>
          </Link>
        ))}
      </div>

      {/* Recent issued */}
      <div className="card overflow-hidden">
        <div className="px-5 py-4 border-b border-gray-100 flex items-center justify-between">
          <h2 className="font-medium text-gray-900">Ostatnio wystawione</h2>
          <Link href="/faktury?direction=ISSUED" className="text-sm text-brand-600 hover:underline">
            Wszystkie
          </Link>
        </div>
        {issued?.content.length === 0 ? (
          <p className="p-5 text-sm text-gray-400">Brak faktur.</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase tracking-wide">
              <tr>
                <th className="px-5 py-3 text-left">Numer</th>
                <th className="px-5 py-3 text-left">Nabywca</th>
                <th className="px-5 py-3 text-left">Data</th>
                <th className="px-5 py-3 text-right">Kwota brutto</th>
                <th className="px-5 py-3 text-left">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {issued?.content.map(inv => (
                <tr key={inv.id} className="hover:bg-gray-50 transition-colors">
                  <td className="px-5 py-3 font-mono text-xs">{inv.invoiceNumber}</td>
                  <td className="px-5 py-3 text-gray-700">{inv.buyerName}</td>
                  <td className="px-5 py-3 text-gray-500">{formatDate(inv.issueDate)}</td>
                  <td className="px-5 py-3 text-right font-medium">{formatPLN(inv.grossAmount)}</td>
                  <td className="px-5 py-3"><StatusBadge status={inv.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
