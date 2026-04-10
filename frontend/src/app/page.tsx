'use client';

import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { FileText, Send, Inbox, AlertCircle, RefreshCw } from 'lucide-react';
import Link from 'next/link';
import { useUser } from '@/lib/user-context';
import { invoicesApi, configApi } from '@/lib/api';
import { formatPLN, formatDate } from '@/lib/utils';
import { StatusBadge } from '@/components/ui/StatusBadge';
import UserSetup from '@/components/forms/UserSetup';

function currentMonthStart() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
}

export default function HomePage() {
  const { user, userId, newReceivedCount, clearNewInvoices } = useUser();
  const queryClient = useQueryClient();
  const [fetchState, setFetchState] = useState<'idle' | 'loading' | 'done' | 'error'>('idle');

  const filters = { issueDateFrom: currentMonthStart(), size: 50 };

  const { data: issued } = useQuery({
    queryKey: ['invoices', userId, 'ISSUED', 'month'],
    queryFn: () => invoicesApi.list(userId, { ...filters, direction: 'ISSUED' }),
    enabled: !!userId,
  });

  const { data: received } = useQuery({
    queryKey: ['invoices', userId, 'RECEIVED', 'month'],
    queryFn: () => invoicesApi.list(userId, { ...filters, direction: 'RECEIVED' }),
    enabled: !!userId,
  });

  const { data: config } = useQuery({
    queryKey: ['config'],
    queryFn: configApi.get,
    staleTime: Infinity,
  });

  // Gdy użytkownik wchodzi na stronę główną, wyczyść alert nowych faktur
  useEffect(() => {
    if (received && newReceivedCount > 0) {
      clearNewInvoices(received.totalElements);
    }
  }, [received, newReceivedCount, clearNewInvoices]);

  const handleFetchFromKsef = async () => {
    setFetchState('loading');
    try {
      await invoicesApi.fetchFromKsef(userId);
      setTimeout(() => {
        queryClient.invalidateQueries({ queryKey: ['invoices', userId] });
        setFetchState('done');
        setTimeout(() => setFetchState('idle'), 3000);
      }, 3000);
    } catch {
      setFetchState('error');
      setTimeout(() => setFetchState('idle'), 3000);
    }
  };

  if (!user) return <UserSetup />;

  const failedCount = (issued?.content ?? []).filter(i => i.status === 'FAILED').length;

  const stats = [
    {
      label: 'Wystawione (ten miesiąc)',
      value: issued?.totalElements ?? '—',
      icon: Send,
      color: 'text-blue-600 bg-blue-50',
      href: '/faktury?direction=ISSUED',
    },
    {
      label: 'Odebrane (ten miesiąc)',
      value: received?.totalElements ?? '—',
      icon: Inbox,
      color: 'text-purple-600 bg-purple-50',
      href: '/faktury?direction=RECEIVED',
    },
    {
      label: 'Błędy',
      value: failedCount,
      icon: AlertCircle,
      color: 'text-red-600 bg-red-50',
      href: '/faktury',
    },
  ];

  const hasNewReceived = newReceivedCount > 0;

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">{user.companyName}</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            NIP: {user.nip} · {config?.ksefEnvironment === 'prod' ? 'Środowisko produkcyjne KSeF' : 'Środowisko testowe KSeF'}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={handleFetchFromKsef}
            disabled={fetchState === 'loading' || !user?.hasKsefToken}
            title={!user?.hasKsefToken ? 'Brak tokenu KSeF — dodaj go w Konfiguracji' : 'Sprawdź nowe faktury w KSeF'}
            className="btn-secondary"
          >
            <RefreshCw size={15} className={fetchState === 'loading' ? 'animate-spin' : ''} />
            {fetchState === 'loading' ? 'Sprawdzanie KSeF…' : fetchState === 'done' ? 'Odświeżono ✓' : fetchState === 'error' ? 'Odświeżono' : 'Sprawdź KSeF'}
          </button>
          <Link href="/faktury/nowa" className="btn-primary">
            <FileText size={16} />
            Nowa faktura
          </Link>
        </div>
      </div>

      {/* Alert nowych faktur przychodzących */}
      {hasNewReceived && (
        <div className="flex items-center gap-3 px-4 py-3 bg-amber-50 border border-amber-200 rounded-lg text-amber-800 text-sm">
          <AlertCircle size={18} className="text-amber-500 shrink-0" />
          <span>
            Masz <strong>{newReceivedCount}</strong> {newReceivedCount === 1 ? 'nową fakturę przychodząca' : 'nowe faktury przychodzące'} od ostatniego logowania.
          </span>
          <Link href="/faktury?direction=RECEIVED" className="ml-auto text-amber-700 font-medium hover:underline whitespace-nowrap">
            Zobacz
          </Link>
        </div>
      )}

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

      {/* Wystawione w bieżącym miesiącu */}
      <div className="card overflow-hidden">
        <div className="px-5 py-4 border-b border-gray-100 flex items-center justify-between">
          <h2 className="font-medium text-gray-900">Wystawione w bieżącym miesiącu</h2>
          <Link href="/faktury?direction=ISSUED" className="text-sm text-brand-600 hover:underline">
            Wszystkie
          </Link>
        </div>
        {!issued || issued.content.length === 0 ? (
          <p className="p-5 text-sm text-gray-400">Brak wystawionych faktur w tym miesiącu.</p>
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
              {issued.content.map(inv => (
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

      {/* Odebrane w bieżącym miesiącu */}
      <div className="card overflow-hidden">
        <div className="px-5 py-4 border-b border-gray-100 flex items-center justify-between">
          <h2 className="font-medium text-gray-900 flex items-center gap-2">
            Odebrane w bieżącym miesiącu
            {hasNewReceived && (
              <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-amber-100 text-amber-700 text-xs font-medium rounded-full">
                <AlertCircle size={11} />
                Nowe
              </span>
            )}
          </h2>
          <Link href="/faktury?direction=RECEIVED" className="text-sm text-brand-600 hover:underline">
            Wszystkie
          </Link>
        </div>
        {!received || received.content.length === 0 ? (
          <p className="p-5 text-sm text-gray-400">Brak odebranych faktur w tym miesiącu.</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase tracking-wide">
              <tr>
                <th className="px-5 py-3 text-left">Numer</th>
                <th className="px-5 py-3 text-left">Sprzedawca</th>
                <th className="px-5 py-3 text-left">Data</th>
                <th className="px-5 py-3 text-right">Kwota brutto</th>
                <th className="px-5 py-3 text-left">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {received.content.map(inv => (
                <tr key={inv.id} className="hover:bg-gray-50 transition-colors">
                  <td className="px-5 py-3 font-mono text-xs">{inv.invoiceNumber}</td>
                  <td className="px-5 py-3 text-gray-700">{inv.sellerName}</td>
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
